package org.openrewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.openrewrite.ExecutionContext;
import org.openrewrite.config.Environment;
import org.openrewrite.TreeSerializer;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.maven.tree.Maven;
import org.openrewrite.maven.utilities.PrintMavenAsCycloneDxBom;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.util.stream.Collectors.toList;

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

    @Parameter(name = "skipCycloneDxBom", property = "skipCycloneDxBom", defaultValue = "false")
    private boolean skipCycloneDxBom;

    @Override
    public void execute() throws MojoExecutionException {
        Environment env = environment();
        ExecutionContext ctx = executionContext();
        File rewriteJar = buildAstJar(env, ctx);
        projectHelper.attachArtifact(project, rewriteJar, "ast");

        if (!skipCycloneDxBom) {
            File cycloneDxBom = buildCycloneDxBom(ctx);
            projectHelper.attachArtifact(project, "xml", "cyclonedx", cycloneDxBom);
        }
    }

    private File buildAstJar(Environment env, ExecutionContext executionContext) throws MojoExecutionException {
        List<Path> javaSources = new ArrayList<>();
        javaSources.addAll(listJavaSources(project.getBuild().getSourceDirectory()));
        javaSources.addAll(listJavaSources(project.getBuild().getTestSourceDirectory()));

        List<Path> dependencies = project.getArtifacts().stream()
                .map(d -> d.getFile().toPath())
                .collect(toList());

        List<J.CompilationUnit> sourceFiles = JavaParser.fromJavaVersion()
                .styles(env.activateStyles(activeStyles))
                .classpath(dependencies)
                .logCompilationWarningsAndErrors(false)
                .build()
                .parse(javaSources, project.getBasedir().toPath(), executionContext);

        File outputDir = new File(project.getBuild().getDirectory());

        // when running the plugin in tests/maven-invoker-plugin, the output directory does not seem
        // to exist. Create it. For normal Maven plugin executions, this should not be triggered.
        if (!outputDir.exists()) {
            boolean created = outputDir.mkdir();
            if (!created) {
                throw new MojoExecutionException("Unable to create directory to write ast.jar into: " + outputDir);
            }
        }

        File rewriteJar = new File(outputDir,
                project.getArtifactId() + "-" + project.getVersion() + "-ast.jar");

        TreeSerializer<J.CompilationUnit> serializer = new TreeSerializer<>();

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(rewriteJar))) {
            for (J.CompilationUnit cu : sourceFiles) {
                ZipEntry entry = new ZipEntry(Paths.get(cu.getSourcePath().toString()).toString());
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

    private File buildCycloneDxBom(ExecutionContext executionContext) {
        List<Path> allPoms = new ArrayList<>();
        allPoms.add(project.getFile().toPath());

        // parents
        MavenProject parent = project.getParent();
        while (parent != null && parent.getFile() != null) {
            allPoms.add(parent.getFile().toPath());
            parent = parent.getParent();
        }

        try {
            Maven pomAst = MavenParser.builder()
                    .resolveOptional(false)
                    .build()
                    .parse(allPoms, project.getBasedir().toPath(), executionContext)
                    .iterator()
                    .next();

            File cycloneDxBom = new File(project.getBuild().getDirectory(),
                    project.getArtifactId() + "-" + project.getVersion() + "-cyclonedx.xml");

            Files.write(cycloneDxBom.toPath(), PrintMavenAsCycloneDxBom.print(pomAst)
                    .getBytes(StandardCharsets.UTF_8));

            return cycloneDxBom;
        } catch (Throwable t) {
            // TODO we aren't yet confident enough in this to not squash exceptions
            getLog().warn("Unable to produce CycloneDX BOM", t);
            return null;
//            throw new MojoExecutionException("Failed to write CycloneDX BOM", e);
        }
    }
}
