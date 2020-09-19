package org.openrewrite.maven;

import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.openrewrite.Environment;
import org.openrewrite.TreeSerializer;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.maven.tree.Maven;
import org.openrewrite.maven.tree.MavenModel;
import org.openrewrite.maven.utilities.PrintMavenAsCycloneDxBom;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

/**
 * Produce an AST JAR and Cyclone DX BOM for publication to Maven repositories
 */
@Mojo(name = "publish", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true,
        defaultPhase = LifecyclePhase.PACKAGE)
public class RewritePublishMojo extends AbstractRewriteMojo {
    /**
     * The Maven project helper.
     */
    @Component
    private MavenProjectHelper projectHelper;

    @Override
    public void execute() throws MojoExecutionException {
        Environment env = environment();

        File rewriteJar = buildAstJar(env);
        File cycloneDxBom = buildCycloneDxBom();

        projectHelper.attachArtifact(project, rewriteJar, "ast");
        projectHelper.attachArtifact(project, "xml", "cyclonedx", cycloneDxBom);
    }

    private File buildAstJar(Environment env) throws MojoExecutionException {
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

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(rewriteJar))) {
            for (J.CompilationUnit cu : sourceFiles) {
                ZipEntry entry = new ZipEntry(cu.getSourcePath());
                zos.putNextEntry(entry);
                zos.write(serializer.write(cu));
                zos.closeEntry();
            }
            zos.flush();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write AST JAR", e);
        }

        return rewriteJar;
    }

    private File buildCycloneDxBom() throws MojoExecutionException {
        List<Path> allPoms = new ArrayList<>();
        allPoms.add(project.getFile().toPath());

        // parents
        MavenProject parent = project.getParent();
        while (parent != null && parent.getFile() != null) {
            allPoms.add(parent.getFile().toPath());
            parent = parent.getParent();
        }

        Maven.Pom pomAst = MavenParser.builder()
                .resolveDependencies(false)
                .build()
                .parse(allPoms, project.getBasedir().toPath())
                .iterator()
                .next();

        pomAst = pomAst.withModel(pomAst.getModel()
                .withTransitiveDependenciesByScope(project.getDependencies().stream()
                        .collect(
                                Collectors.groupingBy(
                                        Dependency::getScope,
                                        Collectors.mapping(dep -> new MavenModel.ModuleVersionId(
                                                        dep.getGroupId(),
                                                        dep.getArtifactId(),
                                                        dep.getClassifier(),
                                                        dep.getVersion(),
                                                        "jar"
                                                ),
                                                toSet()
                                        )
                                )
                        )
                )
        );

        File cycloneDxBom = new File(project.getBuild().getDirectory(),
                project.getArtifactId() + "-" + project.getVersion() + "-cyclonedx.xml");

        try {
            Files.write(cycloneDxBom.toPath(), new PrintMavenAsCycloneDxBom().visit(pomAst)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write CycloneDX BOM", e);
        }

        return cycloneDxBom;
    }
}
