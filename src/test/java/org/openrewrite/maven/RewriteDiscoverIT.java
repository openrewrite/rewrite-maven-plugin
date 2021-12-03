package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.extension.SystemProperty;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import org.junit.jupiter.api.Nested;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:discover")
@SuppressWarnings("NewClassNamingConvention")
public class RewriteDiscoverIT {

    @Nested
    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:discover")
    class RecipeLookup {

        @MavenTest
        @SystemProperty(value = "detail", content = "true")
        void rewrite_discover_detail(MavenExecutionResult result) {
            assertThat(result)
                    .isSuccessful()
                    .out()
                    .info()
                    .matches(logLines ->
                            logLines.stream().anyMatch(logLine -> logLine.contains("options"))
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
        @SystemProperty(value = "recursion", content = "1")
        void rewrite_discover_recursion(MavenExecutionResult result) {
            assertThat(result)
                    .isSuccessful()
                    .out()
                    .info()
                    .matches(logLines ->
                            logLines.stream().anyMatch(logLine -> logLine.contains("recipeList"))
                    );

            assertThat(result).out().warn().isEmpty();
        }
    }

    @MavenTest
    void rewrite_discover_default(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .info()
                .matches(logLines ->
                        logLines.stream().anyMatch(logLine -> logLine.contains("org.openrewrite.java.format.AutoFormat"))
                )
                .matches(logLines ->
                        logLines.stream().anyMatch(logLine -> logLine.contains("org.openrewrite.java.SpringFormat"))
                );

        assertThat(result).out().warn().isEmpty();
    }

    @MavenTest
    void rewrite_discover_rewrite_yml(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .info()
                .matches(logLines ->
                        logLines.stream().anyMatch(logLine -> logLine.contains("com.example.RewriteDiscoverIT.CodeCleanup"))
                );

        assertThat(result).out().warn().isEmpty();
    }

}
