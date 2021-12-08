package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.*;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenOption(MavenCLIOptions.NO_TRANSFER_PROGRESS)
@SuppressWarnings("NewClassNamingConvention")
public class BasicIT {

    @MavenTest
    void groupid_artifactid_should_be_ok(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .containsOnly("JAR will be empty - no content was marked for inclusion!");
    }

    @MavenTest
    @SystemProperty(value = "ossrh_snapshots_url", content = "https://oss.sonatype.org/content/repositories/snapshots")
    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
    void resolves_maven_properties_from_user_provided_system_properties(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .allSatisfy(line -> assertThat(line).doesNotContain("Invalid repository URL ${ossrh_snapshots_url}"))
                .allSatisfy(line -> assertThat(line).doesNotContain("Unable to resolve property ${ossrh_snapshots_url}"));
    }

    @MavenTest
    @MavenOption(value = MavenCLIOptions.SETTINGS, parameter = "settings-user.xml")
    @MavenProfile("example_profile_id")
    @SystemProperty(value = "REPOSITORY_URL", content = "https://maven-eu.nuxeo.org/nexus/content/repositories/public/")
    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
    void resolves_settings(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .plain()
                .allSatisfy(line -> assertThat(line).doesNotContain("Illegal character in path at index 1"));
    }

    @MavenTest
    @MavenOption(value = MavenCLIOptions.SETTINGS, parameter = "settings-user.xml")
    @MavenProfile("example_profile_id")
    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
    void tolerates_incomplete_urls_in_settings(MavenExecutionResult result) {
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
