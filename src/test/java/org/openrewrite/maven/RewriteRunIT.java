package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.*;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenOption(MavenCLIOptions.NO_TRANSFER_PROGRESS)
@DisabledOnOs(OS.WINDOWS)
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:run")
@SuppressWarnings("NewClassNamingConvention")
class RewriteRunIT {

    @MavenTest
    void multi_module_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .anySatisfy(line -> assertThat(line).contains("org.openrewrite.java.cleanup.FinalizeLocalVariables"));
    }

    @MavenTest
    void single_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .anySatisfy(line -> assertThat(line).contains("org.openrewrite.java.format.AutoFormat"));
    }

    @MavenTest
    void cloud_suitability_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .anySatisfy(line -> assertThat(line).contains("some.jks"));
    }

    @MavenTest
    void java_upgrade_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .filteredOn(line -> line.contains("Changes have been made"))
                .hasSize(1);
    }

    @MavenTest
    void java_compiler_plugin_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .filteredOn(line -> line.contains("Changes have been made"))
                .hasSize(1);
    }

}
