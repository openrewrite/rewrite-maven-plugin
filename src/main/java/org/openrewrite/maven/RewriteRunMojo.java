/*
 * Copyright 2020 the original author or authors.
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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.openrewrite.Result;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the configured recipes and applies the changes locally.
 */
@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
@Execute(phase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class RewriteRunMojo extends AbstractRewriteMojo {
    @Override
    public void execute() throws MojoExecutionException {
        ResultsContainer results = listResults();

        if (results.isNotEmpty()) {
            for (Result result : results.generated) {
                assert result.getAfter() != null;
                getLog().warn("Generated new file " +
                        result.getAfter().getSourcePath().normalize() +
                        " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.deleted) {
                assert result.getBefore() != null;
                getLog().warn("Deleted file " +
                        result.getBefore().getSourcePath().normalize() +
                        " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.moved) {
                assert result.getAfter() != null;
                assert result.getBefore() != null;
                getLog().warn("File has been moved from " +
                        result.getBefore().getSourcePath().normalize() + " to " +
                        result.getAfter().getSourcePath().normalize() + " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.refactoredInPlace) {
                assert result.getBefore() != null;
                getLog().warn("Changes have been made to " +
                        result.getBefore().getSourcePath().normalize() +
                        " by:");
                logRecipesThatMadeChanges(result);
            }

            getLog().warn("Please review and commit the results.");

            try {
                for (Result result : results.generated) {
                    assert result.getAfter() != null;
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                            results.getProjectRoot().resolve(result.getAfter().getSourcePath()))) {
                        sourceFileWriter.write(result.getAfter().print());
                    }
                }
                for (Result result : results.deleted) {
                    assert result.getBefore() != null;
                    Path originalLocation = results.getProjectRoot().resolve(result.getBefore().getSourcePath()).normalize();
                    boolean deleteSucceeded = originalLocation.toFile().delete();
                    if (!deleteSucceeded) {
                        throw new IOException("Unable to delete file " + originalLocation.toAbsolutePath());
                    }
                }
                for (Result result : results.moved) {
                    // Should we try to use git to move the file first, and only if that fails fall back to this?
                    assert result.getBefore() != null;
                    Path originalLocation = results.getProjectRoot().resolve(result.getBefore().getSourcePath());
                    boolean deleteSucceeded = originalLocation.toFile().delete();
                    if (!deleteSucceeded) {
                        throw new IOException("Unable to delete file " + originalLocation.toAbsolutePath());
                    }
                    assert result.getAfter() != null;
                    // Ensure directories exist in case something was moved into a hitherto non-existent package
                    Path afterLocation = results.getProjectRoot().resolve(result.getAfter().getSourcePath());
                    File parentDir = afterLocation.toFile().getParentFile();
                    //noinspection ResultOfMethodCallIgnored
                    parentDir.mkdirs();
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(afterLocation)) {
                        sourceFileWriter.write(result.getAfter().print());
                    }
                }
                for (Result result : results.refactoredInPlace) {
                    assert result.getBefore() != null;
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                            results.getProjectRoot().resolve(result.getBefore().getSourcePath()))) {
                        assert result.getAfter() != null;
                        sourceFileWriter.write(result.getAfter().print());
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable to rewrite source files", e);
            }
        }
    }
}
