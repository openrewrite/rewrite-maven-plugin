package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:remove")
@SuppressWarnings("NewClassNamingConvention")
public class RemoveMojoIT {

    @MavenTest
    void single_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .info()
                .matches(logLines ->
                        logLines.stream().anyMatch(logLine -> logLine.contains("Removed rewrite-maven-plugin from"))
                );
    }

}
