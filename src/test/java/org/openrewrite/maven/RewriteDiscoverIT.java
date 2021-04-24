package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.extension.SystemProperty;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:discover")
public class RewriteDiscoverIT {

    @MavenTest
    void rewrite_discover_default_output(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .info()
                .matches(logLines ->
                        logLines.stream().anyMatch(logLine -> logLine.contains("org.openrewrite.java.format.AutoFormat"))
                )
                .matches(logLines ->
                        logLines.stream().noneMatch(logLine -> logLine.contains("Descriptors"))
                );

        assertThat(result).out().warn().isEmpty();
    }

    @MavenTest
    void rewrite_discover_reads_rewrite_yml(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .info()
                .matches(logLines ->
                        logLines.stream().anyMatch(logLine -> logLine.contains("com.example.RewriteDiscoverIT.CodeCleanup"))
                );

        assertThat(result).out().warn().isEmpty();
    }

    @MavenTest
    @SystemProperty(value = "recipe", content = "org.openrewrite.JAVA.format.AutoFormAT")
    void rewrite_discover_recipe_lookup_case_insensitive(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .info()
                .matches(logLines ->
                        logLines.stream().anyMatch(logLine -> logLine.contains("org.openrewrite.java.format.AutoFormat"))
                );
    }

    @MavenTest
    void rewrite_discover_verbose_output(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .info()
                .matches(logLines ->
                        logLines.stream().anyMatch(logLine -> logLine.contains("Descriptors"))
                );

        assertThat(result).out().warn().isEmpty();
    }

}
