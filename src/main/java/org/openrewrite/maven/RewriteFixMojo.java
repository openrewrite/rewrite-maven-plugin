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
import org.apache.maven.plugins.annotations.*;
import org.openrewrite.Change;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;

// https://medium.com/swlh/step-by-step-guide-to-developing-a-custom-maven-plugin-b6e3a0e09966
// https://carlosvin.github.io/posts/creating-custom-maven-plugin/en/#_dependency_injection
// https://developer.okta.com/blog/2019/09/23/tutorial-build-a-maven-plugin
@Mojo(name = "fix", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
@Execute(phase = LifecyclePhase.PROCESS_SOURCES)
public class RewriteFixMojo extends AbstractRewriteMojo {
    @Override
    public void execute() throws MojoExecutionException {
        Collection<Change> changes = listChanges();

        if (!changes.isEmpty()) {
            for (Change change : changes) {
                getLog().warn("Changes have been made to " +
                        change.getOriginal().getSourcePath() +
                        " by:");
                for (String rule : change.getRulesThatMadeChanges()) {
                    getLog().warn("   " + rule);
                }
            }

            getLog().warn("Please review and commit the changes.");

            try {
                for (Change change : changes) {
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                            project.getBasedir().toPath().resolve(change.getOriginal().getSourcePath()))) {
                        sourceFileWriter.write(change.getFixed().print());
                    }
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Unable to rewrite source files", e);
            }
        }
    }
}
