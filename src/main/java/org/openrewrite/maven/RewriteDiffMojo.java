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

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.openrewrite.Result;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

@Mojo(name = "diff", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
@Execute(phase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class RewriteDiffMojo extends AbstractRewriteMojo {
    @Parameter(property = "reportOutputDirectory", defaultValue = "${project.reporting.outputDirectory}/rewrite", required = true)
    private File reportOutputDirectory;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession mavenSession;

    @Override
    public void execute() throws MojoExecutionException {
        ResultsContainer changes = listResults();

        if (changes.isNotEmpty()) {
            for (Result change : changes.generated) {
                getLog().warn("Applying patch would generate new file " +
                        change.getAfter().getSourcePath() +
                        " by:");
                logRecipesThatMadeChanges(change);
            }
            for (Result change : changes.deleted) {
                getLog().warn("Applying patch would delete file " +
                        change.getBefore().getSourcePath() +
                        " by:");
                logRecipesThatMadeChanges(change);
            }
            for (Result change : changes.moved) {
                getLog().warn("Applying patch would move file from " +
                        change.getBefore().getSourcePath() + " to " +
                        change.getAfter().getSourcePath() + " by:");
                logRecipesThatMadeChanges(change);
            }
            for (Result change : changes.refactoredInPlace) {
                getLog().warn("Applying patch would make changes to " +
                        change.getBefore().getSourcePath() +
                        " by:");
                logRecipesThatMadeChanges(change);
            }

            //noinspection ResultOfMethodCallIgnored
            reportOutputDirectory.mkdirs();

            Path patchFile = reportOutputDirectory.toPath().resolve("rewrite.patch");
            try (BufferedWriter writer = Files.newBufferedWriter(patchFile)) {
                Stream.concat(
                        Stream.concat(changes.generated.stream(), changes.deleted.stream()),
                        Stream.concat(changes.moved.stream(), changes.refactoredInPlace.stream())
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
                throw new MojoExecutionException("Unable to generate rewrite diff file.", e);
            }

            getLog().warn("A patch file has been generated. Run 'git apply " +
                    Paths.get(mavenSession.getExecutionRootDirectory()).relativize(patchFile).toString() + "' to apply.");
        }
    }
}
