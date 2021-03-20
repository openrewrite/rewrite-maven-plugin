package org.openrewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProjectHelper;
import org.openrewrite.ExecutionContext;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.maven.tree.Maven;
import org.openrewrite.maven.utilities.PrintMavenAsCycloneDxBom;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Produce a Cyclone DX BOM for publication to Maven repositories
 */
@Mojo(name = "cyclonedx", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true,
        defaultPhase = LifecyclePhase.PACKAGE)
public class CycloneDxBomMojo extends AbstractRewriteMojo {

    @SuppressWarnings("NotNullFieldNotInitialized")
    @Component
    private MavenProjectHelper projectHelper;

    @Override
    public void execute() throws MojoExecutionException {
        ExecutionContext ctx = executionContext();
        Path baseDir = getBaseDir();
        Maven maven = parseMaven(baseDir, ctx);
        File cycloneDxBom = buildCycloneDxBom(maven);
        projectHelper.attachArtifact(project, "xml", "cyclonedx", cycloneDxBom);
    }

    @Nullable
    private File buildCycloneDxBom(Maven pomAst) {
        try {
            File cycloneDxBom = new File(project.getBuild().getDirectory(),
                    project.getArtifactId() + "-" + project.getVersion() + "-cyclonedx.xml");

            Files.write(cycloneDxBom.toPath(), PrintMavenAsCycloneDxBom.print(pomAst)
                    .getBytes(StandardCharsets.UTF_8));

            return cycloneDxBom;
        } catch (Throwable t) {
            // TODO we aren't yet confident enough in this to not squash exceptions
            getLog().warn("Unable to produce CycloneDX BOM", t);
            return null;
        }
    }
}
