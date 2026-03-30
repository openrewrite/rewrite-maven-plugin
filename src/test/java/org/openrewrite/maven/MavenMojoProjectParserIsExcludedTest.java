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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;

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

            assertThat(callIsExcluded(repo, Paths.get("generated.txt")))
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

            assertThat(callIsExcluded(repo, Paths.get("tracked-ignored.txt")))
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

            assertThat(callIsExcluded(repo, Paths.get("target/output.txt")))
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

            assertThat(callIsExcluded(repo, Paths.get("target/output.txt")))
                    .as("tracked file in gitignored directory should NOT be excluded")
                    .isFalse();
        }
    }

    /**
     * Invoke the private {@code isExcluded} method via reflection, creating a
     * minimal {@link MavenMojoProjectParser} instance with only the
     * {@code repository} field set.
     */
    @SuppressWarnings("unchecked")
    private static boolean callIsExcluded(Repository repo, Path path) throws Exception {
        // Allocate instance without calling the constructor
        sun.misc.Unsafe unsafe = getUnsafe();
        MavenMojoProjectParser parser =
                (MavenMojoProjectParser) unsafe.allocateInstance(MavenMojoProjectParser.class);

        // Set the repository field
        Field repoField = MavenMojoProjectParser.class.getDeclaredField("repository");
        repoField.setAccessible(true);
        repoField.set(parser, repo);

        // Invoke isExcluded(Collection<PathMatcher>, Path)
        Method isExcluded = MavenMojoProjectParser.class.getDeclaredMethod(
                "isExcluded", Collection.class, Path.class);
        isExcluded.setAccessible(true);
        return (boolean) isExcluded.invoke(parser, Collections.<PathMatcher>emptyList(), path);
    }

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    private static void writeFile(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
