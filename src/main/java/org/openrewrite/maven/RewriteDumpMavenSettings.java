package org.openrewrite.maven;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.openrewrite.maven.internal.MavenXmlMapper;

import java.nio.file.Path;

@Mojo(name = "dumpMavenSettings", threadSafe = true, requiresProject = false, aggregator = true)
public class RewriteDumpMavenSettings extends AbstractRewriteMojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path baseDir = getBaseDir();
        MavenMojoProjectParser projectParser = new MavenMojoProjectParser(getLog(), baseDir, pomCacheEnabled, pomCacheDirectory, runtime, skipMavenParsing, getExclusions(), getPlainTextMasks(), sizeThresholdMb, mavenSession, settingsDecrypter);
        MavenSettings mavenSettings = projectParser.buildSettings();

        try {
            getLog().info("The effective Maven settings in use by rewrite-maven-plugin are:");
            getLog().info(MavenXmlMapper.writeMapper().writer().writeValueAsString(mavenSettings));
        } catch (JsonProcessingException e) {
            throw new MojoExecutionException("Failed to dump maven settings.", e);
        }
    }
}
