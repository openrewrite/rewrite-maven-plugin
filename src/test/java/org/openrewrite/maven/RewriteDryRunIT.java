package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
public class RewriteDryRunIT {

    @MavenTest
    void multi_module_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .containsOnly(
                        "",
                        "Applying fixes would make results to target/maven-it/org/openrewrite/maven/RewriteDryRunIT/multi_module_project/project/a/src/main/java/sample/SimplifyBooleanSample.java by:",
                        "  org.openrewrite.java.cleanup.FinalizeLocalVariables",
                        "Run 'mvn rewrite:run' to apply the fixes. Afterwards, review and commit the results.",
                        "",
                        "",
                        "Applying fixes would make results to target/maven-it/org/openrewrite/maven/RewriteDryRunIT/multi_module_project/project/b/src/main/java/sample/EmptyBlockSample.java by:",
                        "  org.openrewrite.java.cleanup.FinalizeLocalVariables",
                        "Run 'mvn rewrite:run' to apply the fixes. Afterwards, review and commit the results."
                );
    }

    @MavenTest
    void single_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .containsOnly(
                        "Applying fixes would make results to target/maven-it/org/openrewrite/maven/RewriteDryRunIT/single_project/project/src/main/java/sample/SimplifyBooleanSample.java by:",
                        "  org.openrewrite.java.format.AutoFormat",
                        "Applying fixes would make results to target/maven-it/org/openrewrite/maven/RewriteDryRunIT/single_project/project/src/main/java/sample/EmptyBlockSample.java by:",
                        "  org.openrewrite.java.format.AutoFormat",
                        "Run 'mvn rewrite:run' to apply the fixes. Afterwards, review and commit the results."
                );
    }

}
