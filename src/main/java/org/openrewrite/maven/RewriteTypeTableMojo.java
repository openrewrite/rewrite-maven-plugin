/*
 * Copyright 2025 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.aether.artifact.Artifact;
import org.openrewrite.java.internal.parser.TypeTable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;

import static java.nio.file.Files.exists;

/**
 * Create a TypeTable in `src/main/resources/META-INF/rewrite/classpath.tsv.zip` for `rewrite.recipeArtifactCoordinates`.
 * {@code ./mvnw rewrite:typetable}
 */
@Mojo(name = "typetable", requiresDependencyResolution = ResolutionScope.TEST, defaultPhase = LifecyclePhase.GENERATE_RESOURCES)
@Execute(phase = LifecyclePhase.GENERATE_RESOURCES)
public class RewriteTypeTableMojo extends AbstractRewriteMojo {

    @Override
    public void execute() throws MojoExecutionException {
        Set<String> recipeArtifactCoordinates = getRecipeArtifactCoordinates();
        String srcMainResources = project.getResources().get(0).getDirectory();
        Path tsvFile = Paths.get(srcMainResources).resolve(TypeTable.DEFAULT_RESOURCE_PATH);
        Path parentFile = tsvFile.getParent();
        if (!parentFile.toFile().mkdirs() && !exists(parentFile)) {
            throw new MojoExecutionException("Unable to create " + parentFile);
        }

        try (TypeTable.Writer writer = TypeTable.newWriter(Files.newOutputStream(tsvFile))) {
            for (String recipeArtifactCoordinate : recipeArtifactCoordinates) {
                // Resolve per GAV, to handle multiple versions of the same artifact
                for (Artifact artifact : resolveArtifacts(recipeArtifactCoordinate)) {
                    writer.jar(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion())
                            .write(artifact.getFile().toPath());
                    getLog().info(String.format("Wrote %s", artifact));
                }
            }
            getLog().info("Wrote " + project.getBasedir().toPath().relativize(tsvFile));

        } catch (IOException e) {
            throw new MojoExecutionException("Unable to generate TypeTable", e);
        }
    }

    private Set<Artifact> resolveArtifacts(String recipeArtifactCoordinate) throws MojoExecutionException {
        ArtifactResolver resolver = new ArtifactResolver(repositorySystem, mavenSession);
        Artifact artifact = resolver.createArtifact(recipeArtifactCoordinate);
        return resolver.resolveArtifactsAndDependencies(Collections.singleton(artifact));
    }

}
