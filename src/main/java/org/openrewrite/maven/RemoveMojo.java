/*
 * Copyright 2021 the original author or authors.
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
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.openrewrite.ExecutionContext;
import org.openrewrite.LargeSourceSet;
import org.openrewrite.Result;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.xml.tree.Xml;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Remove rewrite-maven-plugin (or any other plugin) from the project.<br>
 * For example:<br>
 * {@code ./mvnw rewrite:remove}
 */
@Mojo(name = "remove", threadSafe = true)
@Execute
@SuppressWarnings("unused")
public class RemoveMojo extends AbstractRewriteMojo {

    @SuppressWarnings("NotNullFieldNotInitialized")
    @Parameter(property = "groupId", defaultValue = "org.openrewrite.maven")
    protected String groupId;

    @SuppressWarnings("NotNullFieldNotInitialized")
    @Parameter(property = "artifactId", defaultValue = "rewrite-maven-plugin")
    protected String artifactId;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path baseDir = getBuildRoot();
        ExecutionContext ctx = executionContext();
        Xml.Document maven = new MavenMojoProjectParser(getLog(), baseDir, pomCacheEnabled, pomCacheDirectory, runtime, skipMavenParsing, getExclusions(), getPlainTextMasks(), sizeThresholdMb, mavenSession, settingsDecrypter, runPerSubmodule).parseMaven(project, Collections.emptyList(), ctx);
        LargeSourceSet poms = new InMemoryLargeSourceSet(Collections.singletonList(maven));
        List<Result> results = new RemovePlugin(groupId, artifactId)
                .run(poms, ctx)
                .getChangeset()
                .getAllResults();
        if (!results.isEmpty()) {
            Result result = results.get(0);
            assert result.getBefore() != null;
            assert result.getAfter() != null;
            Charset charset = result.getAfter().getCharset() == null ? StandardCharsets.UTF_8 : result.getAfter().getCharset();
            try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                    baseDir.resolve(result.getBefore().getSourcePath()), charset)) {
                sourceFileWriter.write(new String(result.getAfter().printAll().getBytes(charset), charset));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            getLog().info("Removed " + artifactId + " from " + project.getFile().getPath());
        }
    }
}
