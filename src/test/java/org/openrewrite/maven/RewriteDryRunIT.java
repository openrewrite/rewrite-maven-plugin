package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.*;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenOption(MavenCLIOptions.NO_TRANSFER_PROGRESS)
@MavenOption(MavenCLIExtra.MUTE_PLUGIN_VALIDATION_WARNING)
@MavenOption(MavenCLIOptions.DEBUG)
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
class RewriteDryRunIT {

    @MavenTest
    void fail_on_dry_run(MavenExecutionResult result) {
        assertThat(result)
                .isFailure()
                .out()
                .error()
                .anySatisfy(line -> assertThat(line).contains("Applying recipes would make changes"));
    }

    @MavenTest
    void multi_module_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .anySatisfy(line -> assertThat(line).contains("org.openrewrite.java.cleanup.SimplifyBooleanExpression"));
    }

    @MavenTest
    void recipe_order(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .info()
                .anySatisfy(line -> assertThat(line).contains("Using active recipe(s) [com.example.RewriteDryRunIT.CodeCleanup, org.openrewrite.java.format.AutoFormat, org.openrewrite.java.cleanup.SimplifyBooleanExpression]"));
    }

    @MavenTest
    void single_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .anySatisfy(line -> assertThat(line).contains("org.openrewrite.java.format.AutoFormat"));
    }

    @MavenTest
    @SystemProperties({
            @SystemProperty(value = "rewrite.recipeArtifactCoordinates", content = "org.openrewrite.recipe:rewrite-testing-frameworks:2.0.0"),
            @SystemProperty(value = "activeRecipes", content = "org.openrewrite.java.testing.cleanup.AssertTrueNullToAssertNull")
    })
    void no_plugin_in_pom(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .anySatisfy(line -> assertThat(line).contains("org.openrewrite.java.testing.cleanup.AssertTrueNullToAssertNull"));
    }

}
