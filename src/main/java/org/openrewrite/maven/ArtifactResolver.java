/*
 * Copyright 2020 the original author or authors.
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

import org.apache.maven.RepositoryUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.util.artifact.JavaScopes;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ArtifactResolver {
    private final RepositorySystem repositorySystem;
    private final RepositorySystemSession repositorySystemSession;
    private final List<RemoteRepository> remoteRepositories;

    public ArtifactResolver(RepositorySystem repositorySystem, MavenSession session) {
        this.repositorySystem = repositorySystem;
        this.repositorySystemSession = session.getRepositorySession();
        this.remoteRepositories = RepositoryUtils.toRepos(session.getCurrentProject().getRemoteArtifactRepositories());
    }

    public Artifact createArtifact(String coordinates) throws MojoExecutionException {
        String[] parts = coordinates.split(":");
        if (parts.length < 3) {
            throw new MojoExecutionException("Must include at least groupId:artifactId:version in artifact coordinates" + coordinates);
        }

        return new DefaultArtifact(parts[0], parts[1], null, "jar", parts[2]);
    }

    public Set<Artifact> resolveArtifactsAndDependencies(Set<Artifact> artifacts) throws MojoExecutionException {
        if (artifacts.isEmpty()) {
            return Collections.emptySet();
        }

        Set<Artifact> elements = new HashSet<>();
        try {
            List<Dependency> dependencies = artifacts.stream().map(a -> new Dependency(a, JavaScopes.RUNTIME)).collect(Collectors.toList());
            CollectRequest collectRequest =
                    new CollectRequest(dependencies, Collections.emptyList(), remoteRepositories);
            DependencyRequest dependencyRequest = new DependencyRequest();
            dependencyRequest.setCollectRequest(collectRequest);
            DependencyResult dependencyResult =
                    repositorySystem.resolveDependencies(repositorySystemSession, dependencyRequest);

            for (ArtifactResult resolved : dependencyResult.getArtifactResults()) {
                elements.add(resolved.getArtifact());
            }
            return elements;
        } catch (DependencyResolutionException e) {
            throw new MojoExecutionException("Failed to resolve requested artifacts transitive dependencies.", e);
        }
    }
}
