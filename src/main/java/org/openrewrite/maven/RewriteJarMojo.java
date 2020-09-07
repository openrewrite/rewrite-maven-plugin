package org.openrewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultMavenProjectHelper;
import org.openrewrite.Environment;
import org.openrewrite.TreeSerializer;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.util.stream.Collectors.toList;

/**
 * Produce an AST JAR for publication to Maven repositories
 */
@Mojo(name = "jar", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true,
    defaultPhase = LifecyclePhase.PACKAGE)
public class RewriteJarMojo extends AbstractRewriteMojo {

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

        File rewriteJar = new File(project.getBuild().getDirectory(),
                project.getArtifactId() + "-" + project.getVersion() + "-ast.jar");

        TreeSerializer<J.CompilationUnit> serializer = new TreeSerializer<>();

        try(ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(rewriteJar))) {
            for (J.CompilationUnit cu : sourceFiles) {
                ZipEntry entry = new ZipEntry(cu.getSourcePath());
                zos.putNextEntry(entry);
                zos.write(serializer.write(cu));
                zos.closeEntry();
            }
            zos.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        new DefaultMavenProjectHelper().attachArtifact(project, rewriteJar, "ast");
    }
}
