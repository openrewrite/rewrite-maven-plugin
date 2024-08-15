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
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.config.ClasspathScanningLoader;
import org.openrewrite.config.Environment;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.ipc.http.HttpSender;
import org.openrewrite.ipc.http.HttpUrlConnectionSender;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;

@SuppressWarnings("NotNullFieldNotInitialized")
public abstract class AbstractRewriteMojo extends ConfigurableRewriteMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(property = "rewrite.resolvePropertiesInYaml", defaultValue = "true")
    protected boolean resolvePropertiesInYaml;

    @Component
    protected RuntimeInformation runtime;

    @Component
    protected SettingsDecrypter settingsDecrypter;

    @Component
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
                    Properties propertiesToResolve = resolvePropertiesInYaml ? project.getProperties() : new Properties();
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
