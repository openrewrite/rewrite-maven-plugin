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
import java.util.*;

/**
 * New OpenRewrite and Moderne recipe releases are published to the Code Genome Project rather than to Maven Central.
 * Releases already on Maven Central remain there, so a build that resolves recipes with a dynamic version against
 * Maven Central keeps succeeding while silently pinning itself to the last release published there.
 * <p>
 * This detects that situation so the plugin can point the user at the Code Genome Project. It is informational only.
 */
final class CodeGenomeProjectWarning {

    static final String CREDENTIALS_DOCS = "https://codegenomeproject.org/token";

    private CodeGenomeProjectWarning() {
    }

    /**
     * @param requestedRecipeArtifacts the {@code rewrite.recipeArtifactCoordinates} the build asked for
     * @param results                  the outcome of resolving those coordinates and their dependencies
     * @return a warning to log, or {@code null} when recipes cannot be silently stale
     */
    static @Nullable String warningFor(Collection<Artifact> requestedRecipeArtifacts, Collection<ArtifactResult> results) {
        Map<String, String> dynamicVersions = new HashMap<>();
        for (Artifact artifact : requestedRecipeArtifacts) {
            if (isRecipeArtifact(artifact.getGroupId()) && isDynamicVersion(artifact.getVersion())) {
                dynamicVersions.put(artifact.getGroupId() + ":" + artifact.getArtifactId(), artifact.getVersion());
            }
        }
        if (dynamicVersions.isEmpty()) {
            return null;
        }

        Set<String> stale = new LinkedHashSet<>();
        for (ArtifactResult result : results) {
            Artifact resolved = result.getArtifact();
            if (resolved == null || !isMavenCentral(result.getRepository())) {
                continue;
            }
            String requestedVersion = dynamicVersions.get(resolved.getGroupId() + ":" + resolved.getArtifactId());
            if (requestedVersion != null) {
                stale.add(resolved.getGroupId() + ":" + resolved.getArtifactId() + ":" + requestedVersion +
                          " resolved to " + resolved.getVersion());
            }
        }
        if (stale.isEmpty()) {
            return null;
        }

        StringBuilder warning = new StringBuilder("These recipe artifacts resolve from Maven Central, which no longer receives new recipe releases:");
        for (String artifact : stale) {
            warning.append("\n    ").append(artifact);
        }
        return warning
                .append("\nNewer recipe versions are published to the Code Genome Project; configure it in your repositories to stop resolving stale recipes.")
                .append("\nSee ").append(CREDENTIALS_DOCS).append(" for credentials and repository configuration.")
                .toString();
    }

    private static boolean isRecipeArtifact(String group) {
        return "org.openrewrite".equals(group) || group.startsWith("org.openrewrite.") ||
               "io.moderne".equals(group) || group.startsWith("io.moderne.");
    }

    /**
     * A version the user deliberately pinned is left alone; only a version that asks for "whatever is newest" can
     * quietly resolve to the final Maven Central release.
     */
    private static boolean isDynamicVersion(String version) {
        return "LATEST".equals(version) || "RELEASE".equals(version) ||
               version.startsWith("[") || version.startsWith("(");
    }

    /**
     * Unlike Gradle, Maven reports which repository an artifact was actually served by, even when it came from the
     * local repository cache. An internal mirror is substituted into the project's repositories before resolution, so
     * it is reported under its own URL rather than as Maven Central.
     */
    private static boolean isMavenCentral(@Nullable ArtifactRepository repository) {
        if (!(repository instanceof RemoteRepository)) {
            return false;
        }
        try {
            String host = new URI(((RemoteRepository) repository).getUrl()).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            return "repo.maven.apache.org".equals(host) || "repo1.maven.org".equals(host) || "repo2.maven.org".equals(host);
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
