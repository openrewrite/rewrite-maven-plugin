/*
 * Copyright 2024 the original author or authors.
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

import org.apache.maven.plugin.logging.Log;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.maven.tree.*;

import java.util.List;
import java.util.Objects;

class MavenLoggingResolutionEventListener implements ResolutionEventListener {

    private final Log logger;

    public MavenLoggingResolutionEventListener(Log logger) {
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
    }

    @Override
    public void downloadSuccess(ResolvedGroupArtifactVersion gav, @Nullable ResolvedPom containing) {
        if (logger.isDebugEnabled()) {
            logger.debug("Downloaded " + gav + pomContaining(containing));
        }
    }

    @Override
    public void downloadError(GroupArtifactVersion gav, List<String> attemptedUris, @Nullable Pom containing) {
        StringBuilder sb = new StringBuilder("Failed to download " + gav + pomContaining(containing) + ". Attempted URIs:");
        attemptedUris.forEach(uri -> sb.append("\n  - ").append(uri));
        logger.warn(sb);
    }

    @Override
    public void repositoryAccessFailed(String uri, Throwable e) {
        logger.warn("Failed to access maven repository " + uri + " due to: " + e.getMessage());
        logger.debug(e);
    }

    private static String pomContaining(@Nullable Pom containing) {
        return containing != null ? " from " + containing.getGav() : "";
    }

    private static String pomContaining(@Nullable ResolvedPom containing) {
        return containing != null ? " from " + containing.getGav() : "";
    }
}
