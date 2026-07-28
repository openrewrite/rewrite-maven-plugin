/*
 * Copyright 2026 the original author or authors.
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

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.ArtifactRepository;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

class StaleRecipeArtifactWarnerTest {

    private static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2";

    @ParameterizedTest
    @ValueSource(strings = {"LATEST", "RELEASE", "[8,9)"})
    void warnOnUnpinnedRecipeArtifactFromMavenCentral(String version) {
        Artifact requested = artifact("org.openrewrite.recipe", "rewrite-testing-frameworks", version);
        Artifact resolved = artifact("org.openrewrite.recipe", "rewrite-testing-frameworks", "3.19.0");

        assertThat(warning(requested, resolvedFrom(resolved, remote(MAVEN_CENTRAL))))
                .contains("org.openrewrite.recipe:rewrite-testing-frameworks:3.19.0")
                .contains("Code Genome Project")
                .contains("https://docs.openrewrite.org/running-recipes/getting-started");
    }

    @Test
    void warnOnModerneRecipeArtifact() {
        Artifact requested = artifact("io.moderne.recipe", "rewrite-spring", "LATEST");
        Artifact resolved = artifact("io.moderne.recipe", "rewrite-spring", "1.2.3");

        assertThat(warning(requested, resolvedFrom(resolved, remote(MAVEN_CENTRAL)))).isNotNull();
    }

    @Test
    void noWarningOnPinnedVersion() {
        Artifact pinned = artifact("org.openrewrite.recipe", "rewrite-testing-frameworks", "2.0.3");

        assertThat(warning(pinned, resolvedFrom(pinned, remote(MAVEN_CENTRAL)))).isNull();
    }

    @Test
    void noWarningWhenResolvedFromAnInternalMirror() {
        Artifact requested = artifact("org.openrewrite.recipe", "rewrite-testing-frameworks", "LATEST");
        Artifact resolved = artifact("org.openrewrite.recipe", "rewrite-testing-frameworks", "3.19.0");

        assertThat(warning(requested, resolvedFrom(resolved, remote("https://nexus.internal.example.com/repository/maven-central"))))
                .isNull();
    }

    @Test
    void noWarningWhenResolvedFromTheCodeGenomeProject() {
        Artifact requested = artifact("org.openrewrite.recipe", "rewrite-testing-frameworks", "LATEST");
        Artifact resolved = artifact("org.openrewrite.recipe", "rewrite-testing-frameworks", "3.19.0");

        assertThat(warning(requested, resolvedFrom(resolved, remote("https://artifacts.codegenomeproject.org/maven"))))
                .isNull();
    }

    @Test
    void noWarningForThirdPartyRecipeArtifacts() {
        Artifact requested = artifact("com.example.recipe", "rewrite-house-style", "LATEST");
        Artifact resolved = artifact("com.example.recipe", "rewrite-house-style", "1.0.0");

        assertThat(warning(requested, resolvedFrom(resolved, remote(MAVEN_CENTRAL)))).isNull();
    }

    @Test
    void noWarningForTransitiveDependenciesOfAPinnedRecipeArtifact() {
        Artifact pinned = artifact("org.openrewrite.recipe", "rewrite-testing-frameworks", "2.0.3");
        Artifact transitive = artifact("org.openrewrite", "rewrite-java", "8.84.0");

        assertThat(warning(pinned, resolvedFrom(transitive, remote(MAVEN_CENTRAL)))).isNull();
    }

    @Test
    void noWarningWhenTheResolvingRepositoryIsUnknown() {
        Artifact requested = artifact("org.openrewrite.recipe", "rewrite-testing-frameworks", "LATEST");
        Artifact resolved = artifact("org.openrewrite.recipe", "rewrite-testing-frameworks", "3.19.0");

        assertThat(warning(requested, resolvedFrom(resolved, new LocalRepository("target/local-repo")))).isNull();
    }

    private static String warning(Artifact requested, ArtifactResult result) {
        return StaleRecipeArtifactWarner.staleRecipeArtifactWarning(singletonList(requested), singletonList(result));
    }

    private static Artifact artifact(String groupId, String artifactId, String version) {
        return new DefaultArtifact(groupId, artifactId, null, "jar", version);
    }

    private static RemoteRepository remote(String url) {
        return new RemoteRepository.Builder("test", "default", url).build();
    }

    private static ArtifactResult resolvedFrom(Artifact artifact, ArtifactRepository repository) {
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        ArtifactResult result = new ArtifactResult(request);
        result.setArtifact(artifact);
        result.setRepository(repository);
        return result;
    }
}
