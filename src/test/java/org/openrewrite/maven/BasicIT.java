package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.*;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import org.junit.jupiter.api.Disabled;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenOption(MavenCLIOptions.NO_TRANSFER_PROGRESS)
@MavenOption(MavenCLIExtra.MUTE_PLUGIN_VALIDATION_WARNING)
class BasicIT {

    @MavenTest
    void groupid_artifactid_should_be_ok(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .containsOnly("JAR will be empty - no content was marked for inclusion!");
    }

    @Disabled
    @MavenTest
    @MavenOption(value = MavenCLIOptions.SETTINGS, parameter = "settings.xml")
    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
    void null_check_profile_activation(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .info()
                .anySatisfy(line -> assertThat(line).contains("Applying recipes would make no changes. No patch file generated."));

        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                // Ignore warning logged on Mac OS X; https://github.com/openrewrite/rewrite-maven-plugin/issues/506
                .filteredOn(warn -> !"Unable to initialize RocksdbMavenPomCache, falling back to InMemoryMavenPomCache".equals(warn))
                .isEmpty();
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

}
