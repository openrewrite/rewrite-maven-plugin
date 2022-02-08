package org.openrewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Result;
import org.openrewrite.xml.tree.Xml;

import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Mojo(name = "init", threadSafe = true)
@Execute
@SuppressWarnings("unused")
public class InitMojo extends AbstractRewriteMojo {

    @SuppressWarnings("NotNullFieldNotInitialized")
    @Parameter(property = "groupId", defaultValue = "org.openrewrite.maven")
    protected String groupId;

    @SuppressWarnings("NotNullFieldNotInitialized")
    @Parameter(property = "artifactId", defaultValue = "rewrite-maven-plugin")
    protected String artifactId;

    @Parameter(property = "version")
    @Nullable
    protected String version;

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

    @Parameter(property = "rootOnly", defaultValue = "true")
    protected boolean rootOnly;

    @Override
    public void execute() throws MojoExecutionException {
        Path baseDir = getBaseDir();
        if (rootOnly && !project.getBasedir().toPath().equals(baseDir)) {
            getLog().warn("Skipping non-root project " + project.getFile().getPath());
            return;
        }
        ExecutionContext ctx = executionContext();
        MavenParser mp = MavenParser.builder()
                .mavenConfig(baseDir.resolve(".mvn/maven.config"))
                .build();
        List<Xml.Document> poms = mp.parse(Collections.singleton(project.getFile().toPath()), baseDir, ctx);
        List<Result> results = new AddPlugin(groupId, artifactId, getVersion(), getConfiguration(), null, getExecutions())
                .doNext(new ChangePluginDependencies(groupId, artifactId, dependencies))
                .run(poms);
        if (results.isEmpty()) {
            getLog().warn("Plugin " + artifactId + " is already part of the build");
            return;
        }
        Result result = results.get(0);

        assert result.getBefore() != null;
        assert result.getAfter() != null;
        try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                baseDir.resolve(result.getBefore().getSourcePath()))) {
            sourceFileWriter.write(result.getAfter().printAll());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        getLog().info("Added " + artifactId + " to " + project.getFile().getPath());
    }

    protected String getVersion() {
        if (version == null) {
            //noinspection ConstantConditions
            return new Scanner(InitMojo.class.getResourceAsStream("/version.txt"), "UTF-8").next().trim();
        }
        return version;
    }

    @Nullable
    protected String getConfiguration() {
        Set<String> activeRecipes = getActiveRecipes();
        if (configuration == null && !activeRecipes.isEmpty()) {
            configuration = "<configuration>\n<activeRecipes>\n" +
                    activeRecipes.stream()
                            .map(it -> "<recipe>" + it + "</recipe>")
                            .collect(Collectors.joining("\n"))
                    + "</activeRecipes>\n</configuration>";
        }
        return configuration;
    }

    @Nullable
    protected String getExecutions() {
        String executions = null;
        if (executionPhase != null && executionGoals != null) {
            executions = "<executions>\n<execution>\n" +
                    "<phase>" + executionPhase + "</phase>\n"
                    + "<goals>\n"
                    + Arrays.stream(executionGoals.split(","))
                    .map(it -> "<goal>" + it + "</goal>")
                    .collect(Collectors.joining("\n"))
                    + "\n</goals>\n</execution>\n</executions>";
        }
        return executions;
    }
}
