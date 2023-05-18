package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.*;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenOption(MavenCLIOptions.NO_TRANSFER_PROGRESS)
@MavenOption( MavenCLIExtra.MUTE_PLUGIN_VALIDATION_WARNING)
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:remove")
@SuppressWarnings("NewClassNamingConvention")
class RemoveMojoIT {

    @MavenTest
    void single_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .info()
                .anySatisfy(line -> assertThat(line).contains("Removed rewrite-maven-plugin from"));
    }

}
