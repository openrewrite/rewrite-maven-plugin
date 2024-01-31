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

import org.apache.maven.plugin.logging.Log;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.maven.tree.GroupArtifactVersion;
import org.openrewrite.maven.tree.ManagedDependency;
import org.openrewrite.maven.tree.MavenRepository;
import org.openrewrite.maven.tree.Pom;
import org.openrewrite.maven.tree.ResolutionEventListener;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.maven.tree.ResolvedGroupArtifactVersion;
import org.openrewrite.maven.tree.ResolvedPom;
import org.openrewrite.maven.tree.Scope;

import java.util.List;
import java.util.Objects;

public class MavenLoggingResolutionListener implements ResolutionEventListener {

    private final Log logger;

    public MavenLoggingResolutionListener(Log logger) {
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
    }

    @Override
    public void downloadMetadata(GroupArtifactVersion gav) {
        if (logger.isDebugEnabled()) {
            logger.debug("Downloading metadata for " + gav);
        }
    }

    @Override
    public void download(GroupArtifactVersion gav) {
        if (logger.isDebugEnabled()) {
            logger.debug("Downloading " + gav);
        }
    }

    @Override
    public void downloadSuccess(ResolvedGroupArtifactVersion gav, @Nullable ResolvedPom containing) {
        if (logger.isDebugEnabled()) {
            logger.debug("Downloaded " + gav + pomContaining(containing));
        }
    }

    @Override
    public void downloadError(GroupArtifactVersion gav, List<String> attemptedUris, @Nullable Pom containing) {
        if (logger.isInfoEnabled()) {
            StringBuilder sb = new StringBuilder("Failed to download " + gav + pomContaining(containing));
            attemptedUris.forEach(uri -> sb.append("\n  - ").append(uri));
            logger.debug(sb);
        }
    }

    @Override
    public void parent(Pom parent, Pom containing) {
        if (logger.isDebugEnabled()) {
            logger.debug("Resolved parent pom " + containing + pomContaining(containing));
        }
    }

    @Override
    public void dependency(Scope scope, ResolvedDependency resolvedDependency, ResolvedPom containing) {
        if (logger.isDebugEnabled()) {
            logger.debug("Resolved dependency " + resolvedDependency + " with scope " + scope + pomContaining(containing));
        }
    }

    @Override
    public void bomImport(ResolvedGroupArtifactVersion gav, Pom containing) {
        if (logger.isDebugEnabled()) {
            logger.debug("Resolved imported dependency " + gav + pomContaining(containing));
        }
    }

    @Override
    public void property(String key, String value, Pom containing) {
        if (logger.isDebugEnabled()) {
            logger.debug("Resolved property \"" + key + "\" with value \"" + value + "\"" + pomContaining(containing));
        }
    }

    @Override
    public void dependencyManagement(ManagedDependency dependencyManagement, Pom containing) {
        if (logger.isDebugEnabled()) {
            logger.debug("Resolved managed dependency " + dependencyManagement + " on " + pomContaining(containing));
        }
    }

    @Override
    public void repository(MavenRepository mavenRepository, @Nullable ResolvedPom containing) {
        if (logger.isDebugEnabled()) {
            logger.debug("Resolving maven repository " + mavenRepository + pomContaining(containing));
        }
    }

    @Override
    public void repositoryAccessFailed(String uri, Throwable e) {
        logger.info("Failed to access maven repository " + uri, e);
    }

    private static String pomContaining(@Nullable Pom containing) {
        return containing != null ? " on " + containing : "";
    }

    private static String pomContaining(@Nullable ResolvedPom containing) {
        return containing != null ? " on " + containing : "";
    }
}
