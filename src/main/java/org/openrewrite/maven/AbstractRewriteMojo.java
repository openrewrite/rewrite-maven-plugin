package org.openrewrite.maven;

import io.micrometer.core.instrument.Metrics;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.openrewrite.*;
import org.openrewrite.config.ClasspathScanningLoader;
import org.openrewrite.config.Environment;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.ipc.http.HttpSender;
import org.openrewrite.ipc.http.HttpUrlConnectionSender;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.marker.*;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.xml.tree.Xml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

public abstract class AbstractRewriteMojo extends ConfigurableRewriteMojo {

    @SuppressWarnings("NotNullFieldNotInitialized")
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @SuppressWarnings("NotNullFieldNotInitialized")
    @Component
    protected RuntimeInformation runtime;

    @SuppressWarnings("NotNullFieldNotInitialized")
    @Component
    protected SettingsDecrypter settingsDecrypter;

    @SuppressWarnings("NotNullFieldNotInitialized")
    @Component
    protected RepositorySystem repositorySystem;

    @SuppressWarnings("NotNullFieldNotInitialized")
    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession mavenSession;

    private static final String RECIPE_NOT_FOUND_EXCEPTION_MSG = "Could not find recipe '%s' among available recipes";

    protected Environment environment() throws MojoExecutionException {
        return environment(getRecipeArtifactCoordinatesClassloader());
    }

    static class Config {
        final InputStream inputStream;
        final URI uri;

        Config(InputStream inputStream, URI uri) {
            this.inputStream = inputStream;
            this.uri = uri;
        }
    }

    @Nullable
    Config getConfig() throws IOException {
        try {
            URI uri = new URI(configLocation);
            if (uri.getScheme() != null && uri.getScheme().startsWith("http")) {
                HttpSender httpSender = new HttpUrlConnectionSender();
                return new Config(httpSender.get(configLocation).send().getBody(), uri);
            }
        } catch (URISyntaxException e) {
            // Try to load as a path
        }

        Path absoluteConfigLocation = Paths.get(configLocation);
        if (!absoluteConfigLocation.isAbsolute()) {
            absoluteConfigLocation = project.getBasedir().toPath().resolve(configLocation);
        }
        File rewriteConfig = absoluteConfigLocation.toFile();

        if (rewriteConfig.exists()) {
            return new Config(Files.newInputStream(rewriteConfig.toPath()), rewriteConfig.toURI());
        }

        return null;
    }

    protected Environment environment(@Nullable ClassLoader recipeClassLoader) throws MojoExecutionException {
        Environment.Builder env = Environment.builder(project.getProperties());
        if (recipeClassLoader == null) {
            env.scanRuntimeClasspath()
                    .scanUserHome();
        } else {
            env.load(new ClasspathScanningLoader(project.getProperties(), recipeClassLoader));
        }

        try {
            Config rewriteConfig = getConfig();
            if (rewriteConfig != null) {
                try (InputStream is = rewriteConfig.inputStream) {
                    env.load(new YamlResourceLoader(is, rewriteConfig.uri, project.getProperties()));
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to load rewrite configuration", e);
        }

        return env.build();
    }

    protected ExecutionContext executionContext() {
        return new InMemoryExecutionContext(t -> {
            getLog().debug(t);
        });
    }

    protected Path getBuildRoot() {
        Path localRepositoryFolder = Paths.get(mavenSession.getLocalRepository().getBasedir()).normalize();
        Set<Path> baseFolders = new HashSet<>();

        for (MavenProject project : mavenSession.getAllProjects()) {
            collectBasePaths(project, baseFolders, localRepositoryFolder);
        }

        if (!baseFolders.isEmpty()) {
            List<Path> sortedPaths = new ArrayList<>(baseFolders);
            Collections.sort(sortedPaths);
            return sortedPaths.get(0);
        } else {
            return Paths.get(mavenSession.getExecutionRootDirectory());
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

    private void collectBasePaths(MavenProject project, Set<Path> paths, Path localRepository) {
        Path baseDir = project.getBasedir() == null ? null : project.getBasedir().toPath().normalize();
        if (baseDir == null || baseDir.startsWith(localRepository) || paths.contains(baseDir)) {
            return;
        }

        paths.add(baseDir);

        MavenProject parent = project.getParent();
        while (parent != null && parent.getBasedir() != null) {
            collectBasePaths(parent, paths, localRepository);
            parent = parent.getParent();
        }
    }

    protected ResultsContainer listResults() throws MojoExecutionException {
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
            Collection<Validated<Object>> validated = recipe.validateAll();
            List<Validated.Invalid<Object>> failedValidations = validated.stream().map(Validated::failures)
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

            ExecutionContext ctx = executionContext();
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
        MavenMojoProjectParser projectParser = new MavenMojoProjectParser(getLog(), repositoryRoot, pomCacheEnabled, pomCacheDirectory, runtime, skipMavenParsing, getExclusions(), getPlainTextMasks(), sizeThresholdMb, mavenSession, settingsDecrypter, runPerSubmodule);

        Stream<SourceFile> sourceFiles = projectParser.listSourceFiles(project, styles, ctx);
        List<SourceFile> sourceFileList = sourcesWithAutoDetectedStyles(sourceFiles);
        return new InMemoryLargeSourceSet(sourceFileList);
    }

    protected List<Result> runRecipe(Recipe recipe, LargeSourceSet sourceSet, ExecutionContext ctx) {
        getLog().info("Running recipe(s)...");
        return recipe.run(sourceSet, ctx).getChangeset().getAllResults().stream()
                .filter(source -> {
                    // Remove ASTs originating from generated files
                    if (source.getBefore() != null) {
                        return !source.getBefore().getMarkers().findFirst(Generated.class).isPresent();
                    }
                    return true;
                })
                .collect(toList());
    }

    private List<SourceFile> sourcesWithAutoDetectedStyles(Stream<SourceFile> sourceFiles) {
        org.openrewrite.java.style.Autodetect.Detector javaDetector = org.openrewrite.java.style.Autodetect.detect(sourceFiles);
        org.openrewrite.xml.style.Autodetect.Detector xmlDetector = org.openrewrite.xml.style.Autodetect.detect(javaDetector);
        List<SourceFile> sourceFileList = xmlDetector.collect(toList());

        Map<Class<? extends Tree>, NamedStyles> stylesByType = new HashMap<>();
        stylesByType.put(JavaSourceFile.class, javaDetector.build());
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

    @Nullable
    protected URLClassLoader getRecipeArtifactCoordinatesClassloader() throws MojoExecutionException {
        if (getRecipeArtifactCoordinates().isEmpty()) {
            return null;
        }
        ArtifactResolver resolver = new ArtifactResolver(repositorySystem, mavenSession);

        Set<Artifact> artifacts = new HashSet<>();
        for (String coordinate : getRecipeArtifactCoordinates()) {
            artifacts.add(resolver.createArtifact(coordinate));
        }

        Set<Artifact> resolvedArtifacts = resolver.resolveArtifactsAndDependencies(artifacts);
        URL[] urls = resolvedArtifacts.stream()
                .map(Artifact::getFile)
                .map(File::toURI)
                .map(uri -> {
                    try {
                        return uri.toURL();
                    } catch (MalformedURLException e) {
                        throw new RuntimeException("Failed to resolve artifacts from rewrite.recipeArtifactCoordinates", e);
                    }
                })
                .toArray(URL[]::new);

        return new URLClassLoader(
                urls,
                AbstractRewriteMojo.class.getClassLoader()
        );
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
                    if (!result.diff(Paths.get(""), new FencedMarkerPrinter(), true).isEmpty()) {
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

    public static RecipeDescriptor getRecipeDescriptor(String recipe, Collection<RecipeDescriptor> recipeDescriptors) throws MojoExecutionException {
        return recipeDescriptors.stream()
                .filter(r -> r.getName().equalsIgnoreCase(recipe))
                .findAny()
                .orElseThrow(() -> new MojoExecutionException(String.format(RECIPE_NOT_FOUND_EXCEPTION_MSG, recipe)));
    }
}
