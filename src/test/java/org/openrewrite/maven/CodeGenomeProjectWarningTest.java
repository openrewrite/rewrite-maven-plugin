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

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

class CodeGenomeProjectWarningTest {

    private static final RemoteRepository MAVEN_CENTRAL = repository("https://repo.maven.apache.org/maven2/");

    @ParameterizedTest
    @ValueSource(strings = {"LATEST", "RELEASE", "[6.0,7.0)", "(,7.0)"})
    void warnOnDynamicVersionsResolvedFromMavenCentral(String version) {
        String warning = CodeGenomeProjectWarning.warningFor(
                singletonList(artifact("org.openrewrite.recipe", "rewrite-spring", version)),
                singletonList(resolvedFrom(artifact("org.openrewrite.recipe", "rewrite-spring", "6.15.0"), MAVEN_CENTRAL)));

        assertThat(warning)
                .contains("org.openrewrite.recipe:rewrite-spring:" + version)
                .contains("resolved to 6.15.0")
                .contains(CodeGenomeProjectWarning.CREDENTIALS_DOCS);
    }

    @Test
    void warnOnModerneRecipeArtifacts() {
        assertThat(CodeGenomeProjectWarning.warningFor(
                singletonList(artifact("io.moderne.recipe", "rewrite-spring", "LATEST")),
                singletonList(resolvedFrom(artifact("io.moderne.recipe", "rewrite-spring", "6.15.0"), MAVEN_CENTRAL))))
                .contains("io.moderne.recipe:rewrite-spring:LATEST");
    }

    @Test
    void noWarningOnPinnedVersions() {
        Artifact pinned = artifact("org.openrewrite.recipe", "rewrite-spring", "6.15.0");

        assertThat(CodeGenomeProjectWarning.warningFor(
                singletonList(pinned), singletonList(resolvedFrom(pinned, MAVEN_CENTRAL))))
                .isNull();
    }

    @Test
    void noWarningOnUnrelatedArtifacts() {
        assertThat(CodeGenomeProjectWarning.warningFor(
                singletonList(artifact("com.example", "my-recipes", "LATEST")),
                singletonList(resolvedFrom(artifact("com.example", "my-recipes", "1.0.0"), MAVEN_CENTRAL))))
                .isNull();
    }

    @Test
    void noWarningWithoutRecipeArtifacts() {
        assertThat(CodeGenomeProjectWarning.warningFor(emptyList(), emptyList())).isNull();
    }

    @Test
    void noWarningBehindAnInternalMirror() {
        assertThat(CodeGenomeProjectWarning.warningFor(
                singletonList(artifact("org.openrewrite.recipe", "rewrite-spring", "LATEST")),
                singletonList(resolvedFrom(artifact("org.openrewrite.recipe", "rewrite-spring", "6.15.0"),
                        repository("https://artifacts.internal.example.com/maven-central")))))
                .isNull();
    }

    @Test
    void noWarningWhenResolvedFromTheCodeGenomeProject() {
        assertThat(CodeGenomeProjectWarning.warningFor(
                singletonList(artifact("org.openrewrite.recipe", "rewrite-spring", "LATEST")),
                singletonList(resolvedFrom(artifact("org.openrewrite.recipe", "rewrite-spring", "6.15.0"),
                        repository("https://artifacts.codegenomeproject.org/maven")))))
                .isNull();
    }

    @Test
    void noWarningForTransitiveDependenciesOfAPinnedRecipeArtifact() {
        assertThat(CodeGenomeProjectWarning.warningFor(
                singletonList(artifact("org.openrewrite.recipe", "rewrite-spring", "6.15.0")),
                singletonList(resolvedFrom(artifact("org.openrewrite", "rewrite-java", "8.84.0"), MAVEN_CENTRAL))))
                .isNull();
    }

    @Test
    void noWarningWhenTheResolvingRepositoryIsNotRemote() {
        assertThat(CodeGenomeProjectWarning.warningFor(
                singletonList(artifact("org.openrewrite.recipe", "rewrite-spring", "LATEST")),
                singletonList(resolvedFrom(artifact("org.openrewrite.recipe", "rewrite-spring", "6.15.0"),
                        new LocalRepository("target/local-repo")))))
                .isNull();
    }

    private static Artifact artifact(String groupId, String artifactId, String version) {
        return new DefaultArtifact(groupId, artifactId, null, "jar", version);
    }

    private static RemoteRepository repository(String url) {
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
