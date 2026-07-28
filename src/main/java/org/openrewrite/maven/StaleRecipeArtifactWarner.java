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
import org.eclipse.aether.repository.ArtifactRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static java.util.Arrays.asList;

/**
 * OpenRewrite and Moderne recipe artifacts are published to the Code Genome Project; the releases left behind on
 * Maven Central go stale. Users who never configured the Code Genome Project repository still resolve successfully
 * against Maven Central, so an unpinned version silently stops moving forward.
 */
final class StaleRecipeArtifactWarner {

    private static final Set<String> MAVEN_CENTRAL_HOSTS = new HashSet<>(asList(
            "repo.maven.apache.org",
            "repo1.maven.org",
            "repo2.maven.org",
            "central.maven.org"));

    private static final String DOCS_URL = "https://docs.openrewrite.org/running-recipes/getting-started";

    private StaleRecipeArtifactWarner() {
    }

    static @Nullable String staleRecipeArtifactWarning(Collection<Artifact> requested, Collection<ArtifactResult> results) {
        Set<String> unpinned = new HashSet<>();
        for (Artifact artifact : requested) {
            if (isRecipeArtifact(artifact.getGroupId()) && !isPinnedVersion(artifact.getVersion())) {
                unpinned.add(artifact.getGroupId() + ':' + artifact.getArtifactId());
            }
        }
        if (unpinned.isEmpty()) {
            return null;
        }

        for (ArtifactResult result : results) {
            Artifact resolved = result.getArtifact();
            if (resolved != null &&
                    unpinned.contains(resolved.getGroupId() + ':' + resolved.getArtifactId()) &&
                    isMavenCentral(result.getRepository())) {
                return String.format(
                        "Resolved %s:%s:%s from Maven Central, which no longer receives new recipe releases. " +
                        "Newer versions are published to the Code Genome Project; see %s to create a download token and configure the repository.",
                        resolved.getGroupId(), resolved.getArtifactId(), resolved.getVersion(), DOCS_URL);
            }
        }
        return null;
    }

    private static boolean isRecipeArtifact(String groupId) {
        return isGroupOrSubgroup(groupId, "org.openrewrite") || isGroupOrSubgroup(groupId, "io.moderne");
    }

    private static boolean isGroupOrSubgroup(String groupId, String group) {
        return groupId.equals(group) || groupId.startsWith(group + ".");
    }

    private static boolean isPinnedVersion(String version) {
        return !"LATEST".equals(version) && !"RELEASE".equals(version) &&
               !version.startsWith("[") && !version.startsWith("(");
    }

    private static boolean isMavenCentral(@Nullable ArtifactRepository repository) {
        if (!(repository instanceof RemoteRepository)) {
            return false;
        }
        try {
            String host = new URI(((RemoteRepository) repository).getUrl()).getHost();
            return host != null && MAVEN_CENTRAL_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
