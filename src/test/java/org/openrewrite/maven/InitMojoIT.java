package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.*;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenOption(MavenCLIOptions.NO_TRANSFER_PROGRESS)
@MavenOption( MavenCLIExtra.MUTE_PLUGIN_VALIDATION_WARNING)
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:init")
@SystemProperties({
        @SystemProperty(value = "activeRecipes", content = "org.openrewrite.java.testing.junit5.ParameterizedRunnerToParameterized"),
        @SystemProperty(value = "dependencies", content = "org.openrewrite.recipe:rewrite-spring:4.14.1"),
        @SystemProperty(value = "rootOnly", content = "false")
})
@SuppressWarnings("NewClassNamingConvention")
class InitMojoIT {

    @MavenTest
    void single_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .info()
                .anySatisfy(line -> assertThat(line).contains("Added rewrite-maven-plugin to"));
    }

}
