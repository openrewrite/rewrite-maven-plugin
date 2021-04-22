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

/**
 * Generates warnings in the console for any recipes that would suggest changes, but does not make any changes.
 */
@Mojo(name = "dryRun", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true,
        defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
@Execute(phase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class RewriteDryRunMojo extends AbstractRewriteMojo {
    @Override
    public void execute() throws MojoExecutionException {
        ResultsContainer results = listResults();

        if (results.isNotEmpty()) {
            for (Result result : results.generated) {
                assert result.getAfter() != null;
                getLog().warn("Applying fixes would generate new file " +
                        result.getAfter().getSourcePath() +
                        " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.deleted) {
                assert result.getBefore() != null;
                getLog().warn("Applying fixes would delete file " +
                        result.getBefore().getSourcePath() +
                        " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.moved) {
                assert result.getBefore() != null;
                assert result.getAfter() != null;
                getLog().warn("Applying fixes would move file from " +
                        result.getBefore().getSourcePath() + " to " +
                        result.getAfter().getSourcePath() + " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.refactoredInPlace) {
                assert result.getBefore() != null;
                getLog().warn("Applying fixes would make changes to " +
                        result.getBefore().getSourcePath() +
                        " by:");
                logRecipesThatMadeChanges(result);
            }
            getLog().warn("Run 'mvn rewrite:run' to apply the fixes. Afterwards, review and commit the results.");
        }
    }
}
