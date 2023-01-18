package org.openrewrite.maven;

import com.puppycrawl.tools.checkstyle.Checker;
import io.micrometer.core.instrument.Metrics;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.openrewrite.*;
import org.openrewrite.config.ClasspathScanningLoader;
import org.openrewrite.config.Environment;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.ipc.http.HttpSender;
import org.openrewrite.ipc.http.HttpUrlConnectionSender;
import org.openrewrite.java.style.CheckstyleConfigLoader;
import org.openrewrite.marker.Generated;
import org.openrewrite.style.NamedStyles;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static java.util.Collections.*;
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
        URI uri = toUri(configLocation);
        if (uri != null && uri.getScheme() != null && uri.getScheme().startsWith("http")) {
            HttpSender httpSender = new HttpUrlConnectionSender();
            try (HttpSender.Response response = httpSender.get(configLocation).send()) {
                return new Config(response.getBody(), uri);
            }
        } else {
            Path absoluteConfigLocation = Paths.get(configLocation);
            if (!absoluteConfigLocation.isAbsolute()) {
                absoluteConfigLocation = project.getBasedir().toPath().resolve(configLocation);
            }
            File rewriteConfig = absoluteConfigLocation.toFile();

            if (rewriteConfig.exists()) {
                return new Config(Files.newInputStream(rewriteConfig.toPath()), rewriteConfig.toURI());
            }
        }

        return null;
    }

    @Nullable
    private URI toUri(String location) {
        try {
            return new URI(location);
        } catch (URISyntaxException e) {
            return null;
        }
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
            getLog().warn(t.getMessage());
            getLog().debug(t);
        });
    }

    protected Path getBaseDir() {
        Path localRepositoryFolder = Paths.get(mavenSession.getLocalRepository().getBasedir()).normalize();
        Set<Path> baseFolders = new HashSet<>();

        for (MavenProject project : mavenSession.getAllProjects()) {
            collectBasePaths(project, baseFolders, localRepositoryFolder);
        }

        if (!baseFolders.isEmpty()) {
            List<Path> sortedPaths = new ArrayList<>(new ArrayList<>(baseFolders));
            Collections.sort(sortedPaths);
            return sortedPaths.get(0);
        } else {
            return Paths.get(mavenSession.getExecutionRootDirectory());
        }
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

            Path baseDir = getBaseDir();
            getLog().info(String.format("Using active recipe(s) %s", getActiveRecipes()));
            getLog().info(String.format("Using active styles(s) %s", getActiveStyles()));
            if (getActiveRecipes().isEmpty()) {
                return new ResultsContainer(baseDir, emptyList());
            }

            URLClassLoader recipeArtifactCoordinatesClassloader = getRecipeArtifactCoordinatesClassloader();
            if (recipeArtifactCoordinatesClassloader != null) {
                merge(getClass().getClassLoader(), recipeArtifactCoordinatesClassloader);
            }
            Environment env = environment(recipeArtifactCoordinatesClassloader);

            List<NamedStyles> styles;
            styles = env.activateStyles(getActiveStyles());
            try {
                Plugin checkstylePlugin = project.getPlugin("org.apache.maven.plugins:maven-checkstyle-plugin");
                if (checkstyleConfigFile != null && !checkstyleConfigFile.isEmpty()) {
                    styles.add(CheckstyleConfigLoader.loadCheckstyleConfig(Paths.get(checkstyleConfigFile), emptyMap()));
                } else if (checkstyleDetectionEnabled && checkstylePlugin != null) {
                    Object checkstyleConfRaw = checkstylePlugin.getConfiguration();
                    if (checkstyleConfRaw instanceof Xpp3Dom) {
                        Xpp3Dom xmlCheckstyleConf = (Xpp3Dom) checkstyleConfRaw;
                        Xpp3Dom xmlConfigLocation = xmlCheckstyleConf.getChild("configLocation");

                        if (xmlConfigLocation == null) {
                            // When no config location is specified, the maven-checkstyle-plugin falls back on sun_checks.xml
                            try (InputStream is = Checker.class.getResourceAsStream("/sun_checks.xml")) {
                                if (is != null) {
                                    styles.add(CheckstyleConfigLoader.loadCheckstyleConfig(is, emptyMap()));
                                }
                            }
                        } else {
                            Path configPath = Paths.get(xmlConfigLocation.getValue());
                            if (configPath.toFile().exists()) {
                                styles.add(CheckstyleConfigLoader.loadCheckstyleConfig(configPath, emptyMap()));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                getLog().warn("Unable to parse checkstyle configuration. Checkstyle will not inform rewrite execution.", e);
            }

            Recipe recipe = env.activateRecipes(getActiveRecipes());
            if (recipe.getRecipeList().isEmpty()) {
                getLog().warn("No recipes were activated. " +
                              "Activate a recipe with <activeRecipes><recipe>com.fully.qualified.RecipeClassName</recipe></activeRecipes> in this plugin's <configuration> in your pom.xml, " +
                              "or on the command line with -Drewrite.activeRecipes=com.fully.qualified.RecipeClassName");
                return new ResultsContainer(baseDir, emptyList());
            }

            getLog().info("Validating active recipes...");
            Collection<Validated> validated = recipe.validateAll();
            List<Validated.Invalid> failedValidations = validated.stream().map(Validated::failures)
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

            //Parse and collect source files from each project in the maven session.
            MavenMojoProjectParser projectParser = new MavenMojoProjectParser(getLog(), baseDir, pomCacheEnabled, pomCacheDirectory, runtime, skipMavenParsing, getExclusions(), getPlainTextMasks(), sizeThresholdMb, mavenSession, settingsDecrypter);

            List<SourceFile> sourceFiles = new ArrayList<>();
            if (runPerSubmodule) {
                //If running per submodule, parse the source files for only the current project.
                projectParser.resetTypeCache();
                sourceFiles.addAll(projectParser.listSourceFiles(project, styles, ctx));
            } else {
                //If running across all project, iterate and parse source files from each project
                for (MavenProject projectIndex : mavenSession.getProjects()) {
                    sourceFiles.addAll(projectParser.listSourceFiles(projectIndex, styles, ctx));
                }
            }

            getLog().info("Running recipe(s)...");
            List<Result> results = recipe.run(sourceFiles, ctx).getResults().stream()
                    .filter(source -> {
                        // Remove ASTs originating from generated files
                        if (source.getBefore() != null) {
                            return !source.getBefore().getMarkers().findFirst(Generated.class).isPresent();
                        }
                        return true;
                    })
                    .collect(toList());

            Metrics.removeRegistry(meterRegistryProvider.registry());

            return new ResultsContainer(baseDir, results);
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Dependency resolution required", e);
        }
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
        Set<URL> urls = new HashSet<>();
        for (Artifact artifact : resolvedArtifacts) {
            try {
                urls.add(artifact.getFile().toURI().toURL());
            } catch (MalformedURLException e) {
                throw new MojoExecutionException("Failed to resolve artifacts from rewrite.recipeArtifactCoordinates", e);
            }
        }

        return new URLClassLoader(
                urls.toArray(new URL[0]),
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
                    refactoredInPlace.add(result);
                }
            }
        }

        @Nullable
        public Throwable getFirstException() {
            for (Result result : generated) {
                for (Throwable recipeError : result.getRecipeErrors()) {
                    return recipeError;
                }
            }
            for (Result result : deleted) {
                for (Throwable recipeError : result.getRecipeErrors()) {
                    return recipeError;
                }
            }
            for (Result result : moved) {
                for (Throwable recipeError : result.getRecipeErrors()) {
                    return recipeError;
                }
            }
            for (Result result : refactoredInPlace) {
                for (Throwable recipeError : result.getRecipeErrors()) {
                    return recipeError;
                }
            }
            return null;
        }

        public Path getProjectRoot() {
            return projectRoot;
        }

        public boolean isNotEmpty() {
            return !generated.isEmpty() || !deleted.isEmpty() || !moved.isEmpty() || !refactoredInPlace.isEmpty();
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
