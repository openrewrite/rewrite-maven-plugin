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
import org.apache.maven.plugins.annotations.*;
import org.openrewrite.Result;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Generate warnings to the console for any recipe that would make changes, but do not make changes.
 */
@Mojo(name = "dryRun", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true,
        defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class RewriteDryRunMojo extends AbstractRewriteMojo {

    @SuppressWarnings("NotNullFieldNotInitialized")
    @Parameter(property = "reportOutputDirectory", defaultValue = "${project.reporting.outputDirectory}/rewrite")
    private File reportOutputDirectory;

    /**
     * Whether to throw an exception if there are any result changes produced.
     */
    @Parameter(property = "failOnDryRunResults", defaultValue = "false")
    private boolean failOnDryRunResults;

    @Override
    public void execute() throws MojoExecutionException {
        MavenOptsHelper.checkAndLogMissingJvmModuleExports(getLog());
        ResultsContainer results = listResults();

        if (results.isNotEmpty()) {
            for (Result result : results.generated) {
                assert result.getAfter() != null;
                getLog().warn("These recipes would generate new file " +
                        result.getAfter().getSourcePath() +
                        ":");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.deleted) {
                assert result.getBefore() != null;
                getLog().warn("These recipes would delete file " +
                        result.getBefore().getSourcePath() +
                        ":");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.moved) {
                assert result.getBefore() != null;
                assert result.getAfter() != null;
                getLog().warn("These recipes would move file from " +
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

            //noinspection ResultOfMethodCallIgnored
            reportOutputDirectory.mkdirs();

            Path patchFile = reportOutputDirectory.toPath().resolve("rewrite.patch");
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
    }
}
