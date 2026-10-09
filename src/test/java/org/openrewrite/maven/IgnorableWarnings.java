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

/**
 * Warnings that depend on where the build runs rather than on the plugin: the Mac OS X RocksDB warning
 * (https://github.com/openrewrite/rewrite-maven-plugin/issues/506), the Develocity remote build cache warnings
 * emitted when the cache is unavailable (e.g. a 403 on forked PR builds), and the Maven 3.10 / Resolver 2
 * warnings about the CI-provided `codegenome` credentials and repository lookups.
 */
final class IgnorableWarnings {

    private IgnorableWarnings() {
    }

    static boolean isNotIgnorable(String warn) {
        return !"Unable to initialize RocksdbMavenPomCache, falling back to InMemoryMavenPomCache".equals(warn) &&
               !(warn.startsWith("Could not store entry ") && warn.contains("remote build cache")) &&
               !"The remote build cache was disabled during the build due to errors.".equals(warn) &&
               !(warn.startsWith("Using credentials of server ") && warn.contains("<repositoryOrigins>")) &&
               !warn.startsWith("Not applying session authentication to repository ") &&
               !warn.contains("while a cached not-found from a previous attempt suppresses re-checking");
    }
}
