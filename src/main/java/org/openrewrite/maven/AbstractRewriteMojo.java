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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.config.ClasspathScanningLoader;
import org.openrewrite.config.Environment;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.ipc.http.HttpSender;
import org.openrewrite.ipc.http.HttpUrlConnectionSender;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.*;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.xml.tree.Xml;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@SuppressWarnings("NotNullFieldNotInitialized")
public abstract class AbstractRewriteMojo extends ConfigurableRewriteMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(property = "rewrite.resolvePropertiesInYaml", defaultValue = "true")
    protected boolean resolvePropertiesInYaml;

    @Inject
    protected RuntimeInformation runtime;

    @Inject
    protected SettingsDecrypter settingsDecrypter;

    @Inject
    protected RepositorySystem repositorySystem;

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
                //noinspection resource
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
        } else {
            getLog().debug("No rewrite configuration found at " + absoluteConfigLocation);
        }

        return null;
    }

    protected Environment environment(@Nullable ClassLoader recipeClassLoader) throws MojoExecutionException {
        Properties propertiesToResolve = resolvePropertiesInYaml ? project.getProperties() : new Properties();
        Environment.Builder env = Environment.builder(propertiesToResolve);
        if (recipeClassLoader == null) {
            env.scanRuntimeClasspath()
                    .scanUserHome();
        } else {
            env.load(new ClasspathScanningLoader(propertiesToResolve, recipeClassLoader));
        }

        try {
            Config rewriteConfig = getConfig();
            if (rewriteConfig != null) {
                try (InputStream is = rewriteConfig.inputStream) {
                    env.load(new YamlResourceLoader(is, rewriteConfig.uri, propertiesToResolve));
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to load rewrite configuration", e);
        }

        return env.build();
    }

    protected ExecutionContext executionContext() {
        return new InMemoryExecutionContext(t -> getLog().debug(t));
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

        Marker javaAutoDetect = javaDetector.build();
        Marker kotlinAutoDetect = kotlinDetector.build();
        Marker xmlAutoDetect = xmlDetector.build();

        return ListUtils.map(sourceFileList, s -> {
            Markers markers = s.getMarkers();
            if (s instanceof K.CompilationUnit) {
                markers = markers.add(kotlinAutoDetect);
            } else if (s instanceof J.CompilationUnit) {
                markers = markers.add(javaAutoDetect);
            } else if (s instanceof Xml.Document) {
                markers = markers.add(xmlAutoDetect);
            }
            return s.withMarkers(markers);
        });
    }

    protected @Nullable URLClassLoader getRecipeArtifactCoordinatesClassloader() throws MojoExecutionException {
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
}
