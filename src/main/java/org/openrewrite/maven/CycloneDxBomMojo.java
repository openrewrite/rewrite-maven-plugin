package org.openrewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
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
 * Generate a CycloneDx bill of materials outlining the project's dependencies, including transitive dependencies.
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
        Maven maven = new MavenMojoProjectParser(getLog(), baseDir, pomCacheEnabled, pomCacheDirectory, project, runtime, skipMavenParsing, getExclusions(), sizeThresholdMb).parseMaven(ctx);
        if(maven != null) {
            File cycloneDxBom = buildCycloneDxBom(maven);
            projectHelper.attachArtifact(project, "xml", "cyclonedx", cycloneDxBom);
        } else {
            getLog().error("No cyclonedx bom produced");
        }
    }

    @Nullable
    private File buildCycloneDxBom(Maven pomAst) {
        try {
            File cycloneDxBom = new File(project.getBuild().getDirectory(),
                    project.getArtifactId() + "-" + project.getVersion() + "-cyclonedx.xml");

            //noinspection ResultOfMethodCallIgnored
            cycloneDxBom.getParentFile().mkdirs();

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
