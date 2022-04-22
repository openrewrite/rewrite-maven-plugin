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
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.openrewrite.*;
import org.openrewrite.config.ClasspathScanningLoader;
import org.openrewrite.config.Environment;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.java.style.CheckstyleConfigLoader;
import org.openrewrite.marker.Generated;
import org.openrewrite.style.NamedStyles;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
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
        Environment.Builder env = Environment.builder(project.getProperties());
        if (getRecipeArtifactCoordinates().isEmpty()) {
            env.scanRuntimeClasspath()
                    .scanUserHome();
        } else {
            env.load(new ClasspathScanningLoader(project.getProperties(), getRecipeClassloader()));
        }

        Path absoluteConfigLocation = Paths.get(configLocation);
        if (!absoluteConfigLocation.isAbsolute()) {
            absoluteConfigLocation = project.getBasedir().toPath().resolve(configLocation);
        }
        File rewriteConfig = absoluteConfigLocation.toFile();

        if (rewriteConfig.exists()) {
            try (FileInputStream is = new FileInputStream(rewriteConfig)) {
                env.load(new YamlResourceLoader(is, rewriteConfig.toURI(), project.getProperties()));
            } catch (IOException e) {
                throw new MojoExecutionException("Unable to load rewrite configuration", e);
            }
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
        // This property is set by Maven, apparently for both multi and single module builds
        Object maybeMultiModuleDir = System.getProperties().get("maven.multiModuleProjectDirectory");
        try {
            if (maybeMultiModuleDir instanceof String) {
                return Paths.get((String) maybeMultiModuleDir).toRealPath();
            } else {
                // This path should only be taken by tests using AbstractMojoTestCase
                return project.getBasedir().toPath().toRealPath();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
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

            Environment env = environment();

            List<NamedStyles> styles;
            styles = env.activateStyles(getActiveStyles());
            try {
                Plugin checkstylePlugin = project.getPlugin("org.apache.maven.plugins:maven-checkstyle-plugin");
                if (checkstyleConfigFile != null && !checkstyleConfigFile.isEmpty()) {
                    styles.add(CheckstyleConfigLoader.loadCheckstyleConfig(Paths.get(checkstyleConfigFile), emptyMap()));
                } else if (checkstylePlugin != null) {
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
                            styles.add(CheckstyleConfigLoader.loadCheckstyleConfig(configPath, emptyMap()));
                        }

                    }
                }
            } catch (Exception e) {
                getLog().warn("Unable to parse checkstyle configuration. Checkstyle will not inform rewrite execution.", e);
            }

            Recipe recipe = env.activateRecipes(getActiveRecipes());

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
            MavenMojoProjectParser projectParser = new MavenMojoProjectParser(getLog(), baseDir, pomCacheEnabled, pomCacheDirectory, project, runtime, skipMavenParsing, getExclusions(), sizeThresholdMb, mavenSession, settingsDecrypter);
            List<SourceFile> sourceFiles = projectParser.listSourceFiles(styles, ctx);

            getLog().info("Running recipe(s)...");
            List<Result> results = recipe.run(sourceFiles, ctx).stream()
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

    protected URLClassLoader getRecipeClassloader() throws MojoExecutionException {
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
                } else if (result.getBefore() != null && !result.getBefore().getSourcePath().equals(result.getAfter().getSourcePath())) {
                    moved.add(result);
                } else {
                    refactoredInPlace.add(result);
                }
            }
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
