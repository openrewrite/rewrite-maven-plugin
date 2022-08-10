package org.openrewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProjectHelper;
import org.openrewrite.ExecutionContext;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.maven.utilities.PrintMavenAsCycloneDxBom;
import org.openrewrite.xml.tree.Xml;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/**
 * Generate a CycloneDx bill of materials outlining the project's dependencies, including transitive dependencies.
 */
@Mojo(name = "cyclonedx", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true,
        defaultPhase = LifecyclePhase.PACKAGE)
@SuppressWarnings("unused")
public class CycloneDxBomMojo extends AbstractRewriteMojo {

    @SuppressWarnings("NotNullFieldNotInitialized")
    @Component
    private MavenProjectHelper projectHelper;

    @Override
    public void execute() throws MojoExecutionException {
        ExecutionContext ctx = executionContext();
        Path baseDir = getBaseDir();
        Xml.Document maven = new MavenMojoProjectParser(getLog(), baseDir, pomCacheEnabled, pomCacheDirectory, runtime, skipMavenParsing, getExclusions(), getPlainTextMasks(), sizeThresholdMb, mavenSession, settingsDecrypter).parseMaven(project, Collections.emptyList(), ctx);
        if (maven != null) {
            File cycloneDxBom = buildCycloneDxBom(maven);
            projectHelper.attachArtifact(project, "xml", "cyclonedx", cycloneDxBom);
        } else {
            getLog().error("No cyclonedx bom produced");
        }
    }

    @Nullable
    private File buildCycloneDxBom(Xml.Document pomAst) {
        try {
            File cycloneDxBom = new File(project.getBuild().getDirectory(),
                    project.getArtifactId() + "-" + project.getVersion() + "-cyclonedx.xml");

            //noinspection ResultOfMethodCallIgnored
            cycloneDxBom.getParentFile().mkdirs();

            Files.write(cycloneDxBom.toPath(), PrintMavenAsCycloneDxBom.print(pomAst)
                    .getBytes(pomAst.getCharset()));

            return cycloneDxBom;
        } catch (Throwable t) {
            getLog().warn("Unable to produce CycloneDX BOM", t);
            return null;
        }
    }
}
