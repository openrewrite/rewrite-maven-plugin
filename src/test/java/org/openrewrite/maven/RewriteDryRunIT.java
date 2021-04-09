package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import org.junit.jupiter.api.Disabled;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
@Disabled("lock acquisition errors presumably due to parallel running, should be fixed by setting lock directory")
public class RewriteDryRunIT {

    @MavenTest
    void single_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                // FIXME - content too specific?
                .contains(
                        "Applying fixes would make results to target/maven-it/org/openrewrite/maven/RewriteDryRunIT/single_project/project/src/main/java/sample/SimplifyBooleanSample.java by:"
                );
    }

    @MavenTest
    void multi_module_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                // FIXME - content too specific?
                .contains(
                        "Applying fixes would make results to target/maven-it/org/openrewrite/maven/RewriteDryRunIT/multi_module_project/project/b/src/main/java/sample/EmptyBlockSample.java by:"
                );
    }

}
