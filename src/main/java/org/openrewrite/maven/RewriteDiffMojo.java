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
import org.openrewrite.Change;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Mojo(name = "diff", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
@Execute(phase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class RewriteDiffMojo extends AbstractRewriteMojo {
    @Parameter(property = "reportOutputDirectory", defaultValue = "${project.reporting.outputDirectory}/rewrite", required = true)
    private File reportOutputDirectory;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession mavenSession;

    @Override
    public void execute() throws MojoExecutionException {
        ChangesContainer changes = listChanges();

        if (changes.isNotEmpty()) {
            for(Change change : changes.generated) {
                getLog().warn("Applying patch would generate new file " +
                        change.getFixed().getSourcePath() +
                        " by:");
                logVisitorsThatMadeChanges(change);
            }
            for(Change change : changes.deleted) {
                getLog().warn("Applying patch would delete file " +
                        change.getOriginal().getSourcePath() +
                        " by:");
                logVisitorsThatMadeChanges(change);
            }
            for(Change change : changes.moved) {
                getLog().warn("Applying patch would move file from " +
                        change.getOriginal().getSourcePath() + " to " +
                        change.getFixed().getSourcePath() + " by:");
                logVisitorsThatMadeChanges(change);
            }
            for(Change change : changes.refactoredInPlace) {
                getLog().warn("Applying patch would make changes to " +
                        change.getOriginal().getSourcePath() +
                        " by:");
                logVisitorsThatMadeChanges(change);
            }

            //noinspection ResultOfMethodCallIgnored
            reportOutputDirectory.mkdirs();

            Path patchFile = reportOutputDirectory.toPath().resolve("rewrite.patch");
            try (BufferedWriter writer = Files.newBufferedWriter(patchFile)) {
                changes.stream()
                        .map(Change::diff)
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

            getLog().warn("A patch file has been generated. Run 'git apply -f " +
                    Paths.get(mavenSession.getExecutionRootDirectory()).relativize(patchFile).toString() + "' to apply.");
        }
    }
}
