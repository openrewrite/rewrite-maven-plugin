package org.openrewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Result;
import org.openrewrite.maven.tree.Maven;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

@Mojo(name = "remove", threadSafe = true)
@Execute
public class RemoveMojo extends AbstractRewriteMojo {

    @Parameter(property = "groupId", defaultValue = "org.openrewrite.maven")
    protected String groupId;

    @Parameter(property = "artifactId", defaultValue = "rewrite-maven-plugin")
    protected String artifactId;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path baseDir = getBaseDir();
        ExecutionContext ctx = executionContext();
        MavenParser mp = MavenParser.builder()
                .mavenConfig(baseDir.resolve(".mvn/maven.config"))
                .build();
        List<Maven> pom = mp.parse(Collections.singleton(project.getFile().toPath()), baseDir, ctx);
        Result result = new RemovePlugin(groupId, artifactId)
                .run(pom)
                .get(0);

        assert result.getBefore() != null;
        assert result.getAfter() != null;
        try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                baseDir.resolve(result.getBefore().getSourcePath()))) {
            sourceFileWriter.write(result.getAfter().printAll());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        getLog().info("Removed " + artifactId + " from " + project.getFile().getPath());
    }
}
