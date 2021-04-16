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
    @DisabledOnOs(OS.WINDOWS)
    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
    void multi_module_dry_run(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .containsOnly(
                        "Applying fixes would make results to target/maven-it/org/openrewrite/maven/UsingRecipesFromExternalDependenciesIT/multi_module_dry_run/project/a/pom.xml by:",
                        "  org.openrewrite.maven.RemoveDependency",
                        "Run 'mvn rewrite:run' to apply the fixes. Afterwards, review and commit the results.",
                        "Applying fixes would make results to target/maven-it/org/openrewrite/maven/UsingRecipesFromExternalDependenciesIT/multi_module_dry_run/project/b/pom.xml by:",
                        "  org.openrewrite.maven.RemoveDependency",
                        "Run 'mvn rewrite:run' to apply the fixes. Afterwards, review and commit the results."
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
                .containsOnly(
                        "Applying fixes would make results to target/maven-it/org/openrewrite/maven/UsingRecipesFromExternalDependenciesIT/multi_module_dry_run_modules_with_different_recipe_sets/project/a/src/main/java/sample/SampleA.java by:",
                        "  org.openrewrite.java.format.AutoFormat",
                        "Run 'mvn rewrite:run' to apply the fixes. Afterwards, review and commit the results.",
                        "Applying fixes would make results to target/maven-it/org/openrewrite/maven/UsingRecipesFromExternalDependenciesIT/multi_module_dry_run_modules_with_different_recipe_sets/project/b/pom.xml by:",
                        "  org.openrewrite.maven.RemoveDependency",
                        "Run 'mvn rewrite:run' to apply the fixes. Afterwards, review and commit the results."
                );
    }

    @MavenTest
    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:discover")
    void single_project_discover(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .plain()
                .containsSubsequence(
                        "[info] Active Recipes:",
                        "[info]     org.openrewrite.java.testing.junit5.JUnit5BestPractices",
                        "[info] ",
                        "[info] Activatable Recipes:",
                        "[info] Descriptors:",
                        "[info]     org.openrewrite.java.testing.junit5.JUnit5BestPractices"
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
                .containsSubsequence(
                        "[info] Active Recipes:",
                        "[info]     org.openrewrite.java.spring.boot2.SpringBoot2JUnit4to5Migration",
                        "[info]     org.openrewrite.java.testing.junit5.JUnit5BestPractices",
                        "[info] ",
                        "[info] Activatable Recipes:",
                        "[info] Descriptors:",
                        "[info]     org.openrewrite.java.testing.junit5.JUnit5BestPractices",
                        "[info]     org.openrewrite.spring.boot.config.SpringBootConfigurationProperties_2_0"
                );

        assertThat(result).out().warn().isEmpty();
    }

    @MavenTest
    @DisabledOnOs(OS.WINDOWS)
    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:run")
    void single_project_run(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .containsOnly(
                        "Changes have been made to target/maven-it/org/openrewrite/maven/UsingRecipesFromExternalDependenciesIT/single_project_run/project/pom.xml by:",
                        "  org.openrewrite.maven.RemoveDependency",
                        "Please review and commit the results."
                );

        assertThat(
                // assert the resultant pom.xml has an empty dependencies list
                // because "RemoveDependency" removed the junit4 dependency
                result.getMavenProjectResult().getModel().getDependencies()
        ).isEmpty();

    }


}
