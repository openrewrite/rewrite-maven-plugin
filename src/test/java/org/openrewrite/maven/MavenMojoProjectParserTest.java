package org.openrewrite.maven;

import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.marker.JavaVersion;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Fabian Krüger
 */
class MavenMojoProjectParserTest {
    @Test
    @DisplayName("Given No Java version information exists in Maven Then java.specification.version should be used")
    void givenNoJavaVersionInformationExistsInMavenThenJavaSpecificationVersionShouldBeUsed() {
        JavaVersion marker = MavenMojoProjectParser.getSrcTestJavaVersion(new MavenProject());
        assertThat(marker.getSourceCompatibility()).isEqualTo(System.getProperty("java.specification.version"));
        assertThat(marker.getTargetCompatibility()).isEqualTo(System.getProperty("java.specification.version"));
    }
}
