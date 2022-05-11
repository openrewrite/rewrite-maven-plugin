/*
 * Copyright 2021 the original author or authors.
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
import org.apache.maven.plugins.annotations.Parameter;
import org.openrewrite.Result;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Run the configured recipes and apply the changes locally.
 *
 * Base mojo for rewrite:run and rewrite:runNoFork.
 */
public class AbstractRewriteRunMojo extends AbstractRewriteMojo {

    @Override
    public void execute() throws MojoExecutionException {
        MavenOptsHelper.checkAndLogMissingJvmModuleExports(getLog());

        //If the plugin is configured to run over all projects (at the end of the build) only proceed if the plugin
        //is being run on the last project.
        if (!runPerSubmodule && !project.getId().equals(mavenSession.getProjects().get(mavenSession.getProjects().size() - 1).getId())) {
            return;
        }

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
                        Charset charset = result.getAfter().getCharset();
                        sourceFileWriter.write(new String(result.getAfter().printAll().getBytes(charset), charset));
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
                    File originalParentDir = originalLocation.toFile().getParentFile();
                    boolean deleteSucceeded = originalLocation.toFile().delete();
                    if (!deleteSucceeded) {
                        throw new IOException("Unable to delete file " + originalLocation.toAbsolutePath());
                    }
                    assert result.getAfter() != null;
                    // Ensure directories exist in case something was moved into a hitherto non-existent package
                    Path afterLocation = results.getProjectRoot().resolve(result.getAfter().getSourcePath());
                    File afterParentDir = afterLocation.toFile().getParentFile();
                    // Rename the directory if its name case has been changed, e.g. camel case to lower case.
                    if (afterParentDir.exists() && afterParentDir.getAbsolutePath().equalsIgnoreCase((originalParentDir.getAbsolutePath()))
                        && !afterParentDir.getAbsolutePath().equals(originalParentDir.getAbsolutePath())) {
                        if (!originalParentDir.renameTo(afterParentDir)) {
                            throw new RuntimeException("Unable to rename directory from " + originalParentDir.getAbsolutePath() + " To: " + afterParentDir.getAbsolutePath());
                        }
                    } else if (!afterParentDir.exists() && !afterParentDir.mkdirs()) {
                        throw new RuntimeException("Unable to create directory " + afterParentDir.getAbsolutePath());
                    }
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(afterLocation)) {
                        Charset charset = result.getAfter().getCharset();
                        sourceFileWriter.write(new String(result.getAfter().printAll().getBytes(charset), charset));
                    }
                }
                for (Result result : results.refactoredInPlace) {
                    assert result.getBefore() != null;
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                            results.getProjectRoot().resolve(result.getBefore().getSourcePath()))) {
                        assert result.getAfter() != null;
                        Charset charset = result.getAfter().getCharset();
                        sourceFileWriter.write(new String(result.getAfter().printAll().getBytes(charset), charset));
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable to rewrite source files", e);
            }
        }
    }
}
