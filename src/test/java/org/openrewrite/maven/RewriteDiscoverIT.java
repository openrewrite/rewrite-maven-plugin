package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:discover")
public class RewriteDiscoverIT {

    @MavenTest
    void rewrite_discover_has_output(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .plain()
                .containsSubsequence(
                        "[info] Active Recipes:",
                        "[info] Activatable Recipes:",
                        "[info] Descriptors:",
                        "[info]     org.openrewrite.java.format.AutoFormat"
                );

        assertThat(result).out().warn().isEmpty();
    }

    @MavenTest
    void rewrite_discover_reads_rewrite_yml(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .plain()
                .contains("[info]     com.example.RewriteDiscoverIT.CodeCleanup");

        assertThat(result).out().warn().isEmpty();
    }


}
