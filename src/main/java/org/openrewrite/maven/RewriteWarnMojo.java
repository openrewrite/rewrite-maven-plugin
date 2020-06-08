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
import org.openrewrite.Change;
import org.openrewrite.java.tree.J;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

@Mojo(name = "warn", requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
@Execute(phase = LifecyclePhase.PROCESS_SOURCES)
public class RewriteWarnMojo extends AbstractRewriteMojo {
    @Override
    public void execute() throws MojoExecutionException {
        List<Change<J.CompilationUnit>> changes = listChanges();

        if (!changes.isEmpty()) {
            for (Change<J.CompilationUnit> change : changes) {
                getLog().warn("Changes are suggested to " +
                        change.getOriginal().getSourcePath() +
                        " by:");
                for (String rule : change.getRulesThatMadeChanges()) {
                    getLog().warn("   " + rule);
                }
            }

            getLog().warn("Run 'mvn rewrite:fix' to apply the fixes. Afterwards, review and commit the changes.");
        }
    }
}
