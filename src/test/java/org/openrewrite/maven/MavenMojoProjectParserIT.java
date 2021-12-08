package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.*;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@SuppressWarnings("NewClassNamingConvention")
public class MavenMojoProjectParserIT {

    @MavenTest
    @MavenOption(value = MavenCLIOptions.SETTINGS, parameter = "settings.xml")
    @MavenProfile("example_profile_id")
    @SystemProperty(value = "REPOSITORY_URL", content = "https://maven-eu.nuxeo.org/nexus/content/repositories/public/")
    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
    void maven_mojo_project_parser_resolves_settings(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .plain()
                .allSatisfy(line -> assertThat(line).doesNotContain("Illegal character in path at index 1"));
    }

}
