/*
 * Copyright 2020 the original author or authors.
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
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.LargeSourceSet;
import org.openrewrite.PrintOutputCapture;
import org.openrewrite.Recipe;
import org.openrewrite.RecipeRun;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.Validated;
import org.openrewrite.config.Environment;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.tree.J;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.Generated;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.Markup;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.xml.tree.Xml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

public abstract class AbstractRewriteBaseRunMojo extends AbstractRewriteMojo {

    @Parameter(property = "rewrite.exportDatatables", defaultValue = "false")
    protected boolean exportDatatables;

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
            if (recipe.getRecipeList().isEmpty()) {
                getLog().warn("No recipes were activated. " +
                        "Activate a recipe with <activeRecipes><recipe>com.fully.qualified.RecipeClassName</recipe></activeRecipes> in this plugin's <configuration> in your pom.xml, " +
                        "or on the command line with -Drewrite.activeRecipes=com.fully.qualified.RecipeClassName");
                return new ResultsContainer(repositoryRoot, emptyList());
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
            getLog().info(String.format("Printing Available Datatables to: %s", datatableDirectoryPath));
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

        @Nullable
        public RuntimeException getFirstException() {
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
        getLog().warn(recipeString.toString());
        for (RecipeDescriptor rchild : rd.getRecipeList()) {
            logRecipe(rchild, prefix + "    ");
        }
    }
}
