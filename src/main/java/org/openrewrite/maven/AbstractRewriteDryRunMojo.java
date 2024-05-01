/*
 * Copyright 2022 the original author or authors.
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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Result;
import org.openrewrite.internal.lang.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * Base mojo for rewrite:dryRun and rewrite:dryRunNoFork.
 * <p>
 * Generate warnings to the console for any recipe that would make changes, but do not make changes.
 */
public class AbstractRewriteDryRunMojo extends AbstractRewriteBaseRunMojo {

    @Parameter(property = "reportOutputDirectory")
    @Nullable
    private String reportOutputDirectory;

    /**
     * Whether to throw an exception if there are any result changes produced.
     */
    @Parameter(property = "failOnDryRunResults", defaultValue = "false")
    private boolean failOnDryRunResults;

    @Override
    public void execute() throws MojoExecutionException {
        if (rewriteSkip) {
            getLog().info("Skipping execution");
            putState(State.SKIPPED);
            return;
        }
        putState(State.TO_BE_PROCESSED);

        // If the plugin is configured to run over all projects (at the end of the build) only proceed if the plugin
        // is being run on the last project.
        if (!runPerSubmodule && !allProjectsMarked()) {
            getLog().info("REWRITE: Delaying execution to the end of multi-module project for "
                + project.getGroupId() + ":"
                + project.getArtifactId()+ ":"
                + project.getVersion());
            return;
        }

        ExecutionContext ctx = executionContext();
        ResultsContainer results = listResults(ctx);

        RuntimeException firstException = results.getFirstException();
        if (firstException != null) {
            getLog().error("The recipe produced an error. Please report this to the recipe author.");
            throw firstException;
        }

        if (results.isNotEmpty()) {
            for (Result result : results.generated) {
                assert result.getAfter() != null;
                getLog().warn("These recipes would generate a new file " +
                              result.getAfter().getSourcePath() +
                              ":");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.deleted) {
                assert result.getBefore() != null;
                getLog().warn("These recipes would delete a file " +
                              result.getBefore().getSourcePath() +
                              ":");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.moved) {
                assert result.getBefore() != null;
                assert result.getAfter() != null;
                getLog().warn("These recipes would move a file from " +
                              result.getBefore().getSourcePath() + " to " +
                              result.getAfter().getSourcePath() + ":");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.refactoredInPlace) {
                assert result.getBefore() != null;
                getLog().warn("These recipes would make changes to " +
                              result.getBefore().getSourcePath() +
                              ":");
                logRecipesThatMadeChanges(result);
            }

            Path outPath;
            if (reportOutputDirectory != null) {
                outPath = Paths.get(reportOutputDirectory);
            } else if (runPerSubmodule) {
                outPath = Paths.get(project.getBuild().getDirectory()).resolve("rewrite");
            } else {
                outPath = Paths.get(mavenSession.getTopLevelProject().getBuild().getDirectory()).resolve("rewrite");
            }
            try {
                Files.createDirectories(outPath);
            } catch (IOException e) {
                throw new RuntimeException("Could not create the folder [ " + outPath + "].", e);
            }

            Path patchFile = outPath.resolve("rewrite.patch");
            try (BufferedWriter writer = Files.newBufferedWriter(patchFile)) {
                Stream.concat(
                                Stream.concat(results.generated.stream(), results.deleted.stream()),
                                Stream.concat(results.moved.stream(), results.refactoredInPlace.stream())
                        )
                        .map(Result::diff)
                        .forEach(diff -> {
                            try {
                                writer.write(diff + "\n");
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });

            } catch (Exception e) {
                throw new MojoExecutionException("Unable to generate rewrite result.", e);
            }
            getLog().warn("Patch file available:");
            getLog().warn("    " + patchFile.normalize());
            getLog().warn("Run 'mvn rewrite:run' to apply the recipes.");

            if (failOnDryRunResults) {
                throw new MojoExecutionException("Applying recipes would make changes. See logs for more details.");
            }
        } else {
            getLog().info("Applying recipes would make no changes. No patch file generated.");
        }
        putState(State.PROCESSED);
    }
}
