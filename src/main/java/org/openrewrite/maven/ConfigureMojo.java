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
import org.openrewrite.Recipe;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.xml.tree.Xml;

import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Configure rewrite-maven-plugin (or any other plugin) in the project.<br>
 * For example:<br>
 * {@code ./mvnw rewrite:configure -DactiveRecipes=org.openrewrite.java.spring.boot2.SpringBoot1To2Migration -Ddependencies=org.openrewrite.recipe:rewrite-spring:4.17.0}
 */
@Mojo(name = "configure", threadSafe = true)
@Execute
@SuppressWarnings("unused")
public class ConfigureMojo extends AbstractRewriteMojo {

    @SuppressWarnings("NotNullFieldNotInitialized")
    @Parameter(property = "groupId", defaultValue = "org.openrewrite.maven")
    protected String groupId;

    @SuppressWarnings("NotNullFieldNotInitialized")
    @Parameter(property = "artifactId", defaultValue = "rewrite-maven-plugin")
    protected String artifactId;

    @Parameter(property = "configuration")
    @Nullable
    protected String configuration;

    @Parameter(property = "dependencies")
    @Nullable
    protected String dependencies;

    @Parameter(property = "executionPhase")
    @Nullable
    protected String executionPhase;

    @Parameter(property = "executionGoals")
    @Nullable
    protected String executionGoals;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path baseDir = getBuildRoot();

        ExecutionContext ctx = executionContext();
        Xml.Document maven = new MavenMojoProjectParser(getLog(), baseDir, pomCacheEnabled, pomCacheDirectory, runtime, skipMavenParsing, getExclusions(), getPlainTextMasks(), sizeThresholdMb, mavenSession, settingsDecrypter, runPerSubmodule).parseMaven(project, Collections.emptyList(), ctx);
        Recipe recipe = new Recipe() {
            @Override
            public String getDisplayName() {
                return ConfigureMojo.class.getName();
            }

            @Override
            public String getDescription() {
                return ConfigureMojo.class.getName() + " recipe.";
            }

            @Override
            public List<Recipe> getRecipeList() {
                return Arrays.asList(new ChangePluginDependencies(groupId, artifactId, dependencies), new ChangePluginExecutions(groupId, artifactId, getExecutions()));
            }
        };

        List<SourceFile> poms = Collections.singletonList(maven);
        List<Result> results = recipe.run(new InMemoryLargeSourceSet(poms), ctx).getChangeset().getAllResults();
        if (results.isEmpty()) {
            getLog().warn("No changes made to plugin " + artifactId + " configuration");
            return;
        }
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
        getLog().info("Changed " + artifactId + " in " + project.getFile().getPath());
    }

    @Nullable
    protected String getConfiguration() {
        Set<String> activeRecipes = getActiveRecipes();
        if (configuration == null && !activeRecipes.isEmpty()) {
            configuration = "<activeRecipes>\n" +
                    activeRecipes.stream()
                            .map(it -> "<recipe>" + it + "</recipe>")
                            .collect(Collectors.joining("\n"))
                    + "\n</activeRecipes>";
        }
        return configuration;
    }

    @Nullable
    protected String getExecutions() {
        String executions = null;
        if (executionPhase != null && executionGoals != null) {
            executions = "<execution>\n" +
                    "<phase>" + executionPhase + "</phase>\n"
                    + "<goals>\n"
                    + Arrays.stream(executionGoals.split(","))
                    .map(it -> "<goal>" + it + "</goal>")
                    .collect(Collectors.joining("\n"))
                    + "\n</goals>\n</execution>";
        }
        return executions;
    }
}
