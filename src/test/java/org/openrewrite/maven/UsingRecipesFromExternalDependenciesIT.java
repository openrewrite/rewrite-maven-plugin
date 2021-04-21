package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
public class UsingRecipesFromExternalDependenciesIT {

    @MavenTest
    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
    void multi_module_dry_run(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .matches(logLines -> logLines.stream().anyMatch(logLine -> logLine.contains("Applying fixes would make changes")),
                        "Output should indicate that \"Applying fixes would make changes\""
                );
    }

    @MavenTest
    @DisabledOnOs(OS.WINDOWS)
    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
    void multi_module_dry_run_modules_with_different_recipe_sets(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .matches(logLines ->
                        logLines.stream().anyMatch(logLine -> logLine.contains("Applying fixes would make changes")),
                        "Output should indicate that \"Applying fixes would make changes\""
                );
    }

    @MavenTest
    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:discover")
    void single_project_discover(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .plain()
                .matches(logLines ->
                        logLines.stream().anyMatch(logLine -> logLine.contains("org.openrewrite.java.testing.junit5.JUnit5BestPractices")),
                        "Output should indicate that the recipe \"org.openrewrite.java.testing.junit5.JUnit5BestPractices\" made changes"
                );

        assertThat(result).out().warn().isEmpty();
    }

    @MavenTest
    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:discover")
    void single_project_discover_multiple_dependencies(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .plain()
                .matches(logLines ->
                        logLines.stream().anyMatch(logLine -> logLine.contains("org.openrewrite.java.spring.boot2.SpringBoot2JUnit4to5Migration")),
                        "Output should indicate that the recipe \"org.openrewrite.java.spring.boot2.SpringBoot2JUnit4to5Migration\" made changes"
                );

        assertThat(result).out().warn().isEmpty();
    }

    @MavenTest
    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:run")
    void single_project_run(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .matches(logLines ->
                        logLines.stream().anyMatch(logLine -> logLine.contains("org.openrewrite.maven.RemoveDependency"))
                );

        assertThat(
                // assert the resultant pom.xml has an empty dependencies list
                // because "RemoveDependency" removed the junit4 dependency
                result.getMavenProjectResult().getModel().getDependencies()
        ).isEmpty();

    }

}
