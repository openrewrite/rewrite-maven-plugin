/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.*;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
@MavenJupiterExtension
@MavenOption(MavenCLIOptions.NO_TRANSFER_PROGRESS)
@MavenOption(MavenCLIExtra.MUTE_PLUGIN_VALIDATION_WARNING)
@MavenOption(MavenCLIOptions.VERBOSE)
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
                .anySatisfy(line -> assertThat(line).contains("org.openrewrite.staticanalysis.SimplifyBooleanExpression"));
    }

    @MavenTest
    void recipe_order(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .info()
                .anySatisfy(line -> assertThat(line).contains("Using active recipe(s) [com.example.RewriteDryRunIT.CodeCleanup, org.openrewrite.java.format.AutoFormat, org.openrewrite.staticanalysis.SimplifyBooleanExpression]"));
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
            @SystemProperty(value = "rewrite.recipeArtifactCoordinates", content = "org.openrewrite.recipe:rewrite-testing-frameworks:2.0.3"),
            @SystemProperty(value = "rewrite.activeRecipes", content = "org.openrewrite.java.testing.cleanup.AssertTrueNullToAssertNull")
    })
    void no_plugin_in_pom(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .anySatisfy(line -> assertThat(line).contains("org.openrewrite.java.testing.cleanup.AssertTrueNullToAssertNull"));
    }

}
