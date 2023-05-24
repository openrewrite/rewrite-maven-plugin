package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.MavenCLIOptions;
import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenOption;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenOption(MavenCLIOptions.NO_TRANSFER_PROGRESS)
@MavenOption(MavenCLIExtra.MUTE_PLUGIN_VALIDATION_WARNING)
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:discover")
class DiscoverNoActiveRecipeIT {

    @MavenTest
    void single_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .error()
                .noneSatisfy(line -> assertThat(line).contains("Could not find recipe 'null' among available recipes"));
    }
}
