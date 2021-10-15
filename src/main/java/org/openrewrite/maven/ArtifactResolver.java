package org.openrewrite.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.repository.RepositorySystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ArtifactResolver {
  private static final Logger LOG = LoggerFactory.getLogger(ArtifactResolver.class);

  private final RepositorySystem repositorySystem;

  private final ArtifactRepository localRepository;

  private final List<ArtifactRepository> remoteRepositories;

  public ArtifactResolver(RepositorySystem repositorySystem, MavenSession session) {
    this.repositorySystem = repositorySystem;
    this.localRepository = session.getLocalRepository();
    this.remoteRepositories = session.getCurrentProject().getRemoteArtifactRepositories();
  }

  public Artifact createArtifact(String coordinates) throws MojoExecutionException {
    String[] parts = coordinates.split(":");
    if (parts.length < 3) {
      throw new MojoExecutionException("Must include at least groupId:artifactId:version in artifact coordinates" + coordinates);
    }

    return repositorySystem.createArtifact(parts[0], parts[1], parts[2], "runtime", "jar");
  }

  public Set<Artifact> resolveArtifactsAndDependencies(Set<Artifact> artifacts) throws MojoExecutionException {
    if (artifacts.isEmpty()) {
      return Collections.emptySet();
    }

    Set<Artifact> resultArtifacts = new HashSet<>();
    Artifact artifact = artifacts.iterator().next();
    ArtifactResolutionRequest request = new ArtifactResolutionRequest()
        .setArtifact(artifact)
        .setArtifactDependencies(artifacts)
        .setLocalRepository(localRepository)
        .setRemoteRepositories(remoteRepositories)
        .setResolveTransitively(true)
        .setResolveRoot(true);

    ArtifactResolutionResult resolution = repositorySystem.resolve(request);
    if (!resolution.isSuccess()) {
      if (resolution.hasMissingArtifacts()) {
        LOG.warn("Missing artifacts for {}: {}", artifact.getId(), resolution.getMissingArtifacts());
      }
      resolution.getExceptions().forEach(e -> LOG.warn("Failed to resolve artifacts and/or dependencies for {}: {}", artifact.getId(), e.getMessage()));
      throw new MojoExecutionException("Failed to resolve requested artifacts transitive dependencies.");
    } else {
      resultArtifacts.addAll(resolution.getArtifacts());
    }

    return resultArtifacts;
  }
}
