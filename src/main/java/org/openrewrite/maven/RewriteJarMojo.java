package org.openrewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.openrewrite.Environment;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.util.stream.Collectors.toList;

/**
 * Produce an AST JAR for publication to Maven repositories
 */
@Execute(phase = LifecyclePhase.INSTALL)
public class RewriteJarMojo extends AbstractRewriteMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        Environment env = environment();

        List<Path> javaSources = new ArrayList<>();
        javaSources.addAll(listJavaSources(project.getBuild().getSourceDirectory()));
        javaSources.addAll(listJavaSources(project.getBuild().getTestSourceDirectory()));

        List<Path> dependencies = project.getArtifacts().stream()
                .map(d -> d.getFile().toPath())
                .collect(toList());

        List<J.CompilationUnit> sourceFiles = JavaParser.fromJavaVersion()
                .styles(env.styles(activeStyles))
                .classpath(dependencies)
                .logCompilationWarningsAndErrors(false)
                .build()
                .parse(javaSources, project.getBasedir().toPath());

//        File rewriteJar;
//
//        new DefaultMavenProjectHelper().attachArtifact(project, rewriteJar, "ast");
    }
}
