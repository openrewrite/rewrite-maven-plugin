package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenGoal("clean")
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
public class PomCacheIT {

    @MavenTest
    void multi_module_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .project()
                .hasTarget()
                .withFile("pomCache")
                .isDirectory()
                .exists()
                .isNotEmptyDirectory();

        assertThat(result).out().warn().isEmpty();
    }

    @MavenTest
    void single_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .project()
                .hasTarget()
                .withFile("pomCache")
                .isDirectory()
                .exists()
                .isNotEmptyDirectory();

        assertThat(result).out().warn().isEmpty();
    }


}
