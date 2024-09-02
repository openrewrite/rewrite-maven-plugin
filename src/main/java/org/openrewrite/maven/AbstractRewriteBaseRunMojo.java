/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.maven;

import io.micrometer.core.instrument.Metrics;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.config.CompositeRecipe;
import org.openrewrite.config.DeclarativeRecipe;
import org.openrewrite.config.Environment;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.tree.J;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.*;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.xml.tree.Xml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

public abstract class AbstractRewriteBaseRunMojo extends AbstractRewriteMojo {

    @Parameter(property = "rewrite.exportDatatables", defaultValue = "false")
    protected boolean exportDatatables;

    @Parameter(property = "rewrite.options")
    @Nullable
    protected LinkedHashSet<String> options;

    /**
     * The level used to log changes performed by recipes.
     */
    @Parameter(property = "rewrite.recipeChangeLogLevel", defaultValue = "WARN")
    protected LogLevel recipeChangeLogLevel;

    protected void log(CharSequence content) {
        switch (recipeChangeLogLevel) {
            case DEBUG:
                getLog().debug(content);
                break;
            case INFO:
                getLog().info(content);
                break;
            case WARN:
                getLog().warn(content);
                break;
            case ERROR:
                getLog().error(content);
                break;
        }
    }

    /**
     * Attempt to determine the root of the git repository for the given project.
     * Many Gradle builds co-locate the build root with the git repository root, but that is not required.
     * If no git repository can be located in any folder containing the build, the build root will be returned.
     */
    protected Path repositoryRoot() {
        Path buildRoot = getBuildRoot();
        Path maybeBaseDir = buildRoot;
        while (maybeBaseDir != null && !Files.exists(maybeBaseDir.resolve(".git"))) {
            maybeBaseDir = maybeBaseDir.getParent();
        }
        if (maybeBaseDir == null) {
            return buildRoot;
        }
        return maybeBaseDir;
    }

    protected ResultsContainer listResults(ExecutionContext ctx) throws MojoExecutionException {
        try (MeterRegistryProvider meterRegistryProvider = new MeterRegistryProvider(getLog(),
                metricsUri, metricsUsername, metricsPassword)) {
            Metrics.addRegistry(meterRegistryProvider.registry());

            Path repositoryRoot = repositoryRoot();
            getLog().info(String.format("Using active recipe(s) %s", getActiveRecipes()));
            getLog().info(String.format("Using active styles(s) %s", getActiveStyles()));
            if (getActiveRecipes().isEmpty()) {
                return new ResultsContainer(repositoryRoot, emptyList());
            }

            URLClassLoader recipeArtifactCoordinatesClassloader = getRecipeArtifactCoordinatesClassloader();
            if (recipeArtifactCoordinatesClassloader != null) {
                merge(getClass().getClassLoader(), recipeArtifactCoordinatesClassloader);
            }
            Environment env = environment(recipeArtifactCoordinatesClassloader);

            Recipe recipe = env.activateRecipes(getActiveRecipes());
            if (recipe.getName().equals("org.openrewrite.Recipe$Noop")) {
                getLog().warn("No recipes were activated." +
                              " Activate a recipe with <activeRecipes><recipe>com.fully.qualified.RecipeClassName</recipe></activeRecipes> in this plugin's <configuration> in your pom.xml," +
                              " or on the command line with -Drewrite.activeRecipes=com.fully.qualified.RecipeClassName");
                return new ResultsContainer(repositoryRoot, emptyList());
            }

            if (options != null && !options.isEmpty()) {
                configureRecipeOptions(recipe, options);
            }

            getLog().info("Validating active recipes...");
            List<Validated<Object>> validations = new ArrayList<>();
            recipe.validateAll(ctx, validations);
            List<Validated.Invalid<Object>> failedValidations = validations.stream().map(Validated::failures)
                    .flatMap(Collection::stream).collect(toList());
            if (!failedValidations.isEmpty()) {
                failedValidations.forEach(failedValidation -> getLog().error(
                        "Recipe validation error in " + failedValidation.getProperty() + ": " +
                        failedValidation.getMessage(), failedValidation.getException()));
                if (failOnInvalidActiveRecipes) {
                    throw new MojoExecutionException("Recipe validation errors detected as part of one or more activeRecipe(s). Please check error logs.");
                } else {
                    getLog().error("Recipe validation errors detected as part of one or more activeRecipe(s). Execution will continue regardless.");
                }
            }

            LargeSourceSet sourceSet = loadSourceSet(repositoryRoot, env, ctx);

            List<Result> results = runRecipe(recipe, sourceSet, ctx);

            Metrics.removeRegistry(meterRegistryProvider.registry());

            return new ResultsContainer(repositoryRoot, results);
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Dependency resolution required", e);
        }
    }

    private static void configureRecipeOptions(Recipe recipe, Set<String> options) throws MojoExecutionException {
        if (recipe instanceof CompositeRecipe ||
            recipe instanceof DeclarativeRecipe ||
            recipe instanceof Recipe.DelegatingRecipe ||
            !recipe.getRecipeList().isEmpty()) {
            // We don't (yet) support configuring potentially nested recipes, as recipes might occur more than once,
            // and setting the same value twice might lead to unexpected behavior.
            throw new MojoExecutionException(
                    "Recipes containing other recipes can not be configured from the command line: " + recipe);
        }

        Map<String, String> optionValues = new HashMap<>();
        for (String option : options) {
            String[] parts = option.split("=", 2);
            if (parts.length == 2) {
                optionValues.put(parts[0], parts[1]);
            }
        }
        for (Field field : recipe.getClass().getDeclaredFields()) {
            String removed = optionValues.remove(field.getName());
            updateOption(recipe, field, removed);
        }
        if (!optionValues.isEmpty()) {
            throw new MojoExecutionException(
                    String.format("Unknown recipe options: %s", String.join(", ", optionValues.keySet())));
        }
    }

    private static void updateOption(Recipe recipe, Field field, @Nullable String optionValue) throws MojoExecutionException {
        Object convertedOptionValue = convertOptionValue(field.getName(), optionValue, field.getType());
        if (convertedOptionValue == null) {
            return;
        }
        try {
            field.setAccessible(true);
            field.set(recipe, convertedOptionValue);
            field.setAccessible(false);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new MojoExecutionException(
                    String.format("Unable to configure recipe '%s' option '%s' with value '%s'",
                            recipe.getClass().getSimpleName(), field.getName(), optionValue));
        }
    }

    private static @Nullable Object convertOptionValue(String name, @Nullable String optionValue, Class<?> type)
            throws MojoExecutionException {
        if (optionValue == null) {
            return null;
        }
        if (type.isAssignableFrom(String.class)) {
            return optionValue;
        }
        if (type.isAssignableFrom(boolean.class) || type.isAssignableFrom(Boolean.class)) {
            return Boolean.parseBoolean(optionValue);
        }
        if (type.isAssignableFrom(int.class) || type.isAssignableFrom(Integer.class)) {
            return Integer.parseInt(optionValue);
        }
        if (type.isAssignableFrom(long.class) || type.isAssignableFrom(Long.class)) {
            return Long.parseLong(optionValue);
        }

        throw new MojoExecutionException(
                String.format("Unable to convert option: %s value: %s to type: %s", name, optionValue, type));
    }

    protected LargeSourceSet loadSourceSet(Path repositoryRoot, Environment env, ExecutionContext ctx) throws DependencyResolutionRequiredException, MojoExecutionException {
        List<NamedStyles> styles = loadStyles(project, env);

        //Parse and collect source files from each project in the maven session.
        MavenMojoProjectParser projectParser = new MavenMojoProjectParser(getLog(), repositoryRoot, pomCacheEnabled, pomCacheDirectory, runtime, skipMavenParsing, getExclusions(), getPlainTextMasks(), sizeThresholdMb, mavenSession, settingsDecrypter, runPerSubmodule, true);

        Stream<SourceFile> sourceFiles = projectParser.listSourceFiles(project, styles, ctx);
        List<SourceFile> sourceFileList = sourcesWithAutoDetectedStyles(sourceFiles);
        return new InMemoryLargeSourceSet(sourceFileList);
    }

    protected List<Result> runRecipe(Recipe recipe, LargeSourceSet sourceSet, ExecutionContext ctx) {
        getLog().info("Running recipe(s)...");
        RecipeRun recipeRun = recipe.run(sourceSet, ctx);

        if (exportDatatables) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
            Path datatableDirectoryPath = Paths.get("target", "rewrite", "datatables", timestamp);
            getLog().info(String.format("Printing available datatables to: %s", datatableDirectoryPath));
            recipeRun.exportDatatablesToCsv(datatableDirectoryPath, ctx);
        }

        return recipeRun.getChangeset().getAllResults().stream().filter(source -> {
            // Remove ASTs originating from generated files
            if (source.getBefore() != null) {
                return !source.getBefore().getMarkers().findFirst(Generated.class).isPresent();
            }
            return true;
        }).collect(toList());
    }

    private List<SourceFile> sourcesWithAutoDetectedStyles(Stream<SourceFile> sourceFiles) {
        org.openrewrite.java.style.Autodetect.Detector javaDetector = org.openrewrite.java.style.Autodetect.detector();
        org.openrewrite.kotlin.style.Autodetect.Detector kotlinDetector = org.openrewrite.kotlin.style.Autodetect.detector();
        org.openrewrite.xml.style.Autodetect.Detector xmlDetector = org.openrewrite.xml.style.Autodetect.detector();

        List<SourceFile> sourceFileList = sourceFiles
                .peek(s -> {
                    if (s instanceof K.CompilationUnit) {
                        kotlinDetector.sample(s);
                    } else if (s instanceof J.CompilationUnit) {
                        javaDetector.sample(s);
                    }
                })
                .peek(xmlDetector::sample)
                .collect(toList());

        Map<Class<? extends Tree>, NamedStyles> stylesByType = new HashMap<>();
        stylesByType.put(J.CompilationUnit.class, javaDetector.build());
        stylesByType.put(K.CompilationUnit.class, kotlinDetector.build());
        stylesByType.put(Xml.Document.class, xmlDetector.build());

        return ListUtils.map(sourceFileList, applyAutodetectedStyle(stylesByType));
    }

    private UnaryOperator<SourceFile> applyAutodetectedStyle(Map<Class<? extends Tree>, NamedStyles> stylesByType) {
        return before -> {
            for (Map.Entry<Class<? extends Tree>, NamedStyles> styleTypeEntry : stylesByType.entrySet()) {
                if (styleTypeEntry.getKey().isAssignableFrom(before.getClass())) {
                    before = before.withMarkers(before.getMarkers().add(styleTypeEntry.getValue()));
                }
            }
            return before;
        };
    }

    private void merge(ClassLoader targetClassLoader, URLClassLoader sourceClassLoader) {
        ClassRealm targetClassRealm;
        try {
            targetClassRealm = (ClassRealm) targetClassLoader;
        } catch (ClassCastException e) {
            getLog().warn("Could not merge ClassLoaders due to unexpected targetClassLoader type", e);
            return;
        }
        Set<String> existingVersionlessJars = new HashSet<>();
        for (URL existingUrl : targetClassRealm.getURLs()) {
            existingVersionlessJars.add(stripVersion(existingUrl));
        }
        for (URL newUrl : sourceClassLoader.getURLs()) {
            if (!existingVersionlessJars.contains(stripVersion(newUrl))) {
                targetClassRealm.addURL(newUrl);
            }
        }
    }

    private String stripVersion(URL jarUrl) {
        return jarUrl.toString().replaceAll("/[^/]+/[^/]+\\.jar", "");
    }

    public static class ResultsContainer {
        final Path projectRoot;
        final List<Result> generated = new ArrayList<>();
        final List<Result> deleted = new ArrayList<>();
        final List<Result> moved = new ArrayList<>();
        final List<Result> refactoredInPlace = new ArrayList<>();

        public ResultsContainer(Path projectRoot, Collection<Result> results) {
            this.projectRoot = projectRoot;
            for (Result result : results) {
                if (result.getBefore() == null && result.getAfter() == null) {
                    // This situation shouldn't happen / makes no sense, log and skip
                    continue;
                }
                if (result.getBefore() == null && result.getAfter() != null) {
                    generated.add(result);
                } else if (result.getBefore() != null && result.getAfter() == null) {
                    deleted.add(result);
                } else if (result.getBefore() != null && result.getAfter() != null && !result.getBefore().getSourcePath().equals(result.getAfter().getSourcePath())) {
                    moved.add(result);
                } else {
                    if (!result.diff(Paths.get(""), new ResultsContainer.FencedMarkerPrinter(), true).isEmpty()) {
                        refactoredInPlace.add(result);
                    }
                }
            }
        }

        public @Nullable RuntimeException getFirstException() {
            for (Result result : generated) {
                for (RuntimeException error : getRecipeErrors(result)) {
                    return error;
                }
            }
            for (Result result : deleted) {
                for (RuntimeException error : getRecipeErrors(result)) {
                    return error;
                }
            }
            for (Result result : moved) {
                for (RuntimeException error : getRecipeErrors(result)) {
                    return error;
                }
            }
            for (Result result : refactoredInPlace) {
                for (RuntimeException error : getRecipeErrors(result)) {
                    return error;
                }
            }
            return null;
        }

        private List<RuntimeException> getRecipeErrors(Result result) {
            List<RuntimeException> exceptions = new ArrayList<>();
            new TreeVisitor<Tree, Integer>() {
                @Override
                public Tree preVisit(Tree tree, Integer integer) {
                    Markers markers = tree.getMarkers();
                    markers.findFirst(Markup.Error.class).ifPresent(e -> {
                        Optional<SourceFile> sourceFile = Optional.ofNullable(getCursor().firstEnclosing(SourceFile.class));
                        String sourcePath = sourceFile.map(SourceFile::getSourcePath).map(Path::toString).orElse("<unknown>");
                        exceptions.add(new RuntimeException("Error while visiting " + sourcePath + ": " + e.getDetail()));
                    });
                    return tree;
                }
            }.visit(result.getAfter(), 0);
            return exceptions;
        }

        public Path getProjectRoot() {
            return projectRoot;
        }

        public boolean isNotEmpty() {
            return !generated.isEmpty() || !deleted.isEmpty() || !moved.isEmpty() || !refactoredInPlace.isEmpty();
        }

        /**
         * List directories that are empty as a result of applying recipe changes
         */
        public List<Path> newlyEmptyDirectories() {
            Set<Path> maybeEmptyDirectories = new LinkedHashSet<>();
            for (Result result : moved) {
                assert result.getBefore() != null;
                maybeEmptyDirectories.add(projectRoot.resolve(result.getBefore().getSourcePath()).getParent());
            }
            for (Result result : deleted) {
                assert result.getBefore() != null;
                maybeEmptyDirectories.add(projectRoot.resolve(result.getBefore().getSourcePath()).getParent());
            }
            if (maybeEmptyDirectories.isEmpty()) {
                return Collections.emptyList();
            }
            List<Path> emptyDirectories = new ArrayList<>(maybeEmptyDirectories.size());
            for (Path maybeEmptyDirectory : maybeEmptyDirectories) {
                try (Stream<Path> contents = Files.list(maybeEmptyDirectory)) {
                    if (contents.findAny().isPresent()) {
                        continue;
                    }
                    Files.delete(maybeEmptyDirectory);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return emptyDirectories;
        }

        /**
         * Only retains output for markers of type {@code SearchResult} and {@code Markup}.
         */
        private static class FencedMarkerPrinter implements PrintOutputCapture.MarkerPrinter {
            @Override
            public String beforeSyntax(Marker marker, Cursor cursor, UnaryOperator<String> commentWrapper) {
                return marker instanceof SearchResult || marker instanceof Markup ? "{{" + marker.getId() + "}}" : "";
            }

            @Override
            public String afterSyntax(Marker marker, Cursor cursor, UnaryOperator<String> commentWrapper) {
                return marker instanceof SearchResult || marker instanceof Markup ? "{{" + marker.getId() + "}}" : "";
            }
        }
    }

    protected void logRecipesThatMadeChanges(Result result) {
        String indent = "    ";
        String prefix = "    ";
        for (RecipeDescriptor recipeDescriptor : result.getRecipeDescriptorsThatMadeChanges()) {
            logRecipe(recipeDescriptor, prefix);
            prefix = prefix + indent;
        }
    }

    private void logRecipe(RecipeDescriptor rd, String prefix) {
        StringBuilder recipeString = new StringBuilder(prefix + rd.getName());
        if (!rd.getOptions().isEmpty()) {
            String opts = rd.getOptions().stream().map(option -> {
                        if (option.getValue() != null) {
                            return option.getName() + "=" + option.getValue();
                        }
                        return null;
                    }
            ).filter(Objects::nonNull).collect(joining(", "));
            if (!opts.isEmpty()) {
                recipeString.append(": {").append(opts).append("}");
            }
        }
        log(recipeString.toString());
        for (RecipeDescriptor rchild : rd.getRecipeList()) {
            logRecipe(rchild, prefix + "    ");
        }
    }

    protected Duration estimateTimeSavedSum(Result result, Duration timeSaving) {
        if (null != result.getTimeSavings()) {
            return timeSaving.plus(result.getTimeSavings());
        }
        return timeSaving;
    }

    protected String formatDuration(Duration duration) {
        return duration.toString()
                .substring(2)
                .replaceAll("(\\d[HMS])(?!$)", "$1 ")
                .toLowerCase()
                .trim();
    }
}
