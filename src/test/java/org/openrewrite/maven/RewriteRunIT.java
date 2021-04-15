package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:run")
public class RewriteRunIT {

    @MavenTest
    void multi_module_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .containsOnly(
                        "",
                        "Changes have been made to target/maven-it/org/openrewrite/maven/RewriteRunIT/multi_module_project/project/a/src/main/java/sample/SimplifyBooleanSample.java by:",
                        "  org.openrewrite.java.cleanup.FinalizeLocalVariables",
                        "Please review and commit the results.",
                        "",
                        "",
                        "Changes have been made to target/maven-it/org/openrewrite/maven/RewriteRunIT/multi_module_project/project/b/src/main/java/sample/EmptyBlockSample.java by:",
                        "  org.openrewrite.java.cleanup.FinalizeLocalVariables",
                        "Please review and commit the results."
                );
    }

    @MavenTest
    void single_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .containsOnly(
                        "Changes have been made to target/maven-it/org/openrewrite/maven/RewriteRunIT/single_project/project/src/main/java/sample/SimplifyBooleanSample.java by:",
                        "  org.openrewrite.java.format.AutoFormat",
                        "Changes have been made to target/maven-it/org/openrewrite/maven/RewriteRunIT/single_project/project/src/main/java/sample/EmptyBlockSample.java by:",
                        "  org.openrewrite.java.format.AutoFormat",
                        "Please review and commit the results."
                );
    }

}
