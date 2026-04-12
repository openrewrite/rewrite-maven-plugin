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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.jgit.api.Git;
import org.openrewrite.jgit.lib.Repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collection;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the gitignore handling in {@link MavenMojoProjectParser#isExcluded}.
 * <p>
 * The original implementation had a bug where the recursive call that prepends
 * "/" for PathMatcher compatibility caused the JGit TreeWalk path comparison
 * to never match, making the gitignore check dead code. These tests verify
 * that gitignore exclusions actually take effect for relative paths.
 */
class MavenMojoProjectParserIsExcludedTest {

    @Test
    void untrackedGitIgnoredFileIsExcluded(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Repository repo = git.getRepository();

            writeFile(tempDir.resolve(".gitignore"), "generated.txt\n");
            writeFile(tempDir.resolve("generated.txt"), "untracked content");

            git.add().addFilepattern(".gitignore").call();
            git.commit().setMessage("initial").call();

            assertThat(MavenMojoProjectParser.isExcluded(repo, emptyList(), Path.of("generated.txt")))
                    .as("untracked gitignored file should be excluded")
                    .isTrue();
        }
    }

    @Test
    void trackedGitIgnoredFileIsNotExcluded(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Repository repo = git.getRepository();

            writeFile(tempDir.resolve("tracked-ignored.txt"), "content");
            git.add().addFilepattern("tracked-ignored.txt").call();
            git.commit().setMessage("initial").call();

            writeFile(tempDir.resolve(".gitignore"), "tracked-ignored.txt\n");
            git.add().addFilepattern(".gitignore").call();
            git.commit().setMessage("add gitignore").call();

            assertThat(MavenMojoProjectParser.isExcluded(repo, emptyList(), Path.of("tracked-ignored.txt")))
                    .as("tracked gitignored file should NOT be excluded")
                    .isFalse();
        }
    }

    @Test
    void untrackedFileInGitIgnoredDirectoryIsExcluded(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Repository repo = git.getRepository();

            writeFile(tempDir.resolve(".gitignore"), "target/\n");
            writeFile(tempDir.resolve("target/output.txt"), "untracked content");

            git.add().addFilepattern(".gitignore").call();
            git.commit().setMessage("initial").call();

            assertThat(MavenMojoProjectParser.isExcluded(repo, emptyList(), Path.of("target/output.txt")))
                    .as("untracked file in gitignored directory should be excluded")
                    .isTrue();
        }
    }

    @Test
    void trackedFileInGitIgnoredDirectoryIsNotExcluded(@TempDir Path tempDir) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Repository repo = git.getRepository();

            writeFile(tempDir.resolve("target/output.txt"), "tracked content");
            git.add().addFilepattern("target/output.txt").call();
            git.commit().setMessage("initial").call();

            writeFile(tempDir.resolve(".gitignore"), "target/\n");
            git.add().addFilepattern(".gitignore").call();
            git.commit().setMessage("add gitignore").call();

            assertThat(MavenMojoProjectParser.isExcluded(repo, emptyList(), Path.of("target/output.txt")))
                    .as("tracked file in gitignored directory should NOT be excluded")
                    .isFalse();
        }
    }

    @Test
    void exclusionMatcherMatchesDirectly() {
        Collection<PathMatcher> matchers = singletonList(
                FileSystems.getDefault().getPathMatcher("glob:**/secret.properties"));

        assertThat(MavenMojoProjectParser.isExcluded(null, matchers, Path.of("config/secret.properties")))
                .as("path matching exclusion pattern should be excluded")
                .isTrue();
    }

    @Test
    void exclusionMatcherDoesNotMatchUnrelatedPath() {
        Collection<PathMatcher> matchers = singletonList(
                FileSystems.getDefault().getPathMatcher("glob:**/secret.properties"));

        assertThat(MavenMojoProjectParser.isExcluded(null, matchers, Path.of("config/application.properties")))
                .as("path not matching exclusion pattern should not be excluded")
                .isFalse();
    }

    @Test
    void exclusionMatcherMatchesRootFileViaPrefixedSlash() {
        // PathMatcher won't match "pom.xml" against "**/pom.xml" without a leading slash;
        // isExcluded handles this by re-checking with a "/" prefix for relative paths
        Collection<PathMatcher> matchers = singletonList(
                FileSystems.getDefault().getPathMatcher("glob:**/pom.xml"));

        assertThat(MavenMojoProjectParser.isExcluded(null, matchers, Path.of("pom.xml")))
                .as("root-level file should match **/pom.xml via leading-slash prefixing")
                .isTrue();
    }

    @Test
    void exclusionMatcherMatchesRootFileWithLeadingSlash() {
        // When the path already has a leading slash, it should match directly
        // without needing the prefixing logic
        Collection<PathMatcher> matchers = singletonList(
                FileSystems.getDefault().getPathMatcher("glob:**/pom.xml"));

        assertThat(MavenMojoProjectParser.isExcluded(null, matchers, Path.of("/pom.xml")))
                .as("root-level file with leading slash should match **/pom.xml directly")
                .isTrue();
    }

    @Test
    void exclusionMatcherMatchesSubdirFileWithoutPrefixing() {
        // A file in a subdirectory should match directly without needing the "/" prefix path
        Collection<PathMatcher> matchers = singletonList(
                FileSystems.getDefault().getPathMatcher("glob:**/pom.xml"));

        assertThat(MavenMojoProjectParser.isExcluded(null, matchers, Path.of("module/pom.xml")))
                .as("subdirectory file should match **/pom.xml directly")
                .isTrue();
    }


    private static void writeFile(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
