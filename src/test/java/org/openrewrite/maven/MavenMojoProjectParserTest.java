package org.openrewrite.maven;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.internal.DefaultRuntimeInformation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.marker.BuildTool;
import org.openrewrite.marker.Marker;

import java.nio.file.Path;
import java.util.List;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Fabian Krüger
 */
class MavenMojoProjectParserTest {
    @Test
    @DisplayName("Given No Java version information exists in Maven Then java.specification.version should be used")
    void givenNoJavaVersionInformationExistsInMavenThenJavaSpecificationVersionShouldBeUsed(@TempDir Path dir) {
        MavenMojoProjectParser sut = getMavenMojoProjectParser(dir);
        List<Marker> markers = sut.generateProvenance(new MavenProject());
        JavaVersion marker = markers.stream().filter(JavaVersion.class::isInstance).map(JavaVersion.class::cast).findFirst().get();
        assertThat(marker.getSourceCompatibility()).isEqualTo(System.getProperty("java.specification.version"));
        assertThat(marker.getTargetCompatibility()).isEqualTo(System.getProperty("java.specification.version"));

        // The build tool marker should not be present on just any source file, only on the pom.xml & wrapper files
        // https://github.com/openrewrite/rewrite-maven-plugin/issues/649
        assertThat(markers).noneMatch(m -> m instanceof BuildTool);
    }

    private static MavenMojoProjectParser getMavenMojoProjectParser(Path dir) {
        return new MavenMojoProjectParser(
                new SystemStreamLog(),
                dir,
                false,
                null,
                new DefaultRuntimeInformation(),
                false,
                emptyList(),
                emptyList(),
                -1,
                null,
                null,
                false,
                true
        );
    }
}
