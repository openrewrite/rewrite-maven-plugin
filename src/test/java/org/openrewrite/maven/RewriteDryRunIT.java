package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
@SuppressWarnings("NewClassNamingConvention")
public class RewriteDryRunIT {

    @MavenTest
    void fail_on_dry_run(MavenExecutionResult result) {
        assertThat(result)
                .isFailure()
                .out()
                .error()
                .matches(logLines ->
                        logLines.stream().anyMatch(logLine -> logLine.contains("Applying recipes would make changes"))
                );
    }

    @MavenTest
    void multi_module_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .matches(logLines ->
                        logLines.stream().anyMatch(logLine -> logLine.contains("org.openrewrite.java.cleanup.FinalizeLocalVariables"))
                );
    }

    @MavenTest
    void single_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .matches(logLines ->
                        logLines.stream().anyMatch(logLine -> logLine.contains("org.openrewrite.java.format.AutoFormat"))
                );
    }

}
