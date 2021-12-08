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

    @MavenTest
    @MavenOption(value = MavenCLIOptions.SETTINGS, parameter = "settings.xml")
    @MavenProfile("example_profile_id")
    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
    void maven_mojo_project_parser_tolerates_incomplete_urls_in_settings(MavenExecutionResult result) {
        // Note how we left off this annotation for this test:
        // "@SystemProperty(value = "REPOSITORY_URL", content = "https://maven-eu.nuxeo.org/nexus/content/repositories/public/")"
        // which leaves the <url>${REPOSITORY_URL}</url> unresolved in the settings.xml.
        // This tests validates how we handle this situation.
        assertThat(result)
                .isSuccessful()
                .out()
                .plain()
                .allSatisfy(line -> assertThat(line).doesNotContain("Illegal character in path at index 1"));

        assertThat(result)
                .out()
                .warn()
                .hasSize(1)
                .anySatisfy(line -> assertThat(line).contains("Unable to parse URL ${REPOSITORY_URL} for Maven settings repository id example_repository_id"));
    }

}
