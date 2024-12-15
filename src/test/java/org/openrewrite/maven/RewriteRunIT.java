/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Moderne Source Available License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://docs.moderne.io/licensing/moderne-source-available-license
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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenOption(MavenCLIOptions.NO_TRANSFER_PROGRESS)
@MavenOption(MavenCLIExtra.MUTE_PLUGIN_VALIDATION_WARNING)
@DisabledOnOs(OS.WINDOWS)
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:run")
class RewriteRunIT {

    @MavenTest
    void multi_module_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .anySatisfy(line -> assertThat(line).contains("org.openrewrite.staticanalysis.SimplifyBooleanExpression"));
    }

    @MavenTest
    @SystemProperties({
            @SystemProperty(value = "rewrite.activeRecipes", content = "org.openrewrite.java.search.FindTypes"),
            @SystemProperty(value = "rewrite.options", content = "fullyQualifiedTypeName=org.junit.jupiter.api.Test")
    })
    @MavenGoal("generate-test-sources")
    void multi_source_sets_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .contains(
                        "Changes have been made to target/maven-it/org/openrewrite/maven/RewriteRunIT/multi_source_sets_project/project/src/integration-test/java/sample/IntegrationTest.java by:",
                        "Changes have been made to target/maven-it/org/openrewrite/maven/RewriteRunIT/multi_source_sets_project/project/src/test/java/sample/RegularTest.java by:"
                );
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
    void checkstyle_inline_rules(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .noneSatisfy(line -> assertThat(line).contains("Unable to parse checkstyle configuration"));
    }

    @MavenTest
    void recipe_project(MavenExecutionResult result) {
        assertThat(result)
                .isFailure()
                .out()
                .error()
                .anySatisfy(line -> assertThat(line).contains("/sample/ThrowingRecipe.java", "This recipe throws an exception"));
    }

    @MavenTest
    void cloud_suitability_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .anySatisfy(line -> assertThat(line).contains("some.jks"));
    }

    @MavenTest
    @SystemProperties({
            @SystemProperty(value = "rewrite.activeRecipes", content = "org.openrewrite.maven.RemovePlugin"),
            @SystemProperty(value = "rewrite.options", content = "groupId=org.openrewrite.maven,artifactId=rewrite-maven-plugin")
    })
    void command_line_options(MavenExecutionResult result) {
        assertThat(result).isSuccessful().out().error().isEmpty();
        assertThat(result).isSuccessful().out().warn()
                .contains("Changes have been made to target/maven-it/org/openrewrite/maven/RewriteRunIT/command_line_options/project/pom.xml by:")
                .contains("    org.openrewrite.maven.RemovePlugin");
        assertThat(result.getMavenProjectResult().getModel().getBuild()).isNull();
    }

    @MavenTest
    @Disabled("We should implement a simpler test to make sure that regular markers don't get added to source files")
    void java_upgrade_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .filteredOn(line -> line.contains("Changes have been made"))
                .hasSize(1);
    }

    @MavenTest
    void java_compiler_plugin_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .filteredOn(line -> line.contains("Changes have been made"))
                .hasSize(1);
    }

    @MavenTest
    @SystemProperty(value = "rewrite.additionalPlainTextMasks", content = "**/*.ext,**/.in-root")
    void plaintext_masks(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .contains(
                        "Changes have been made to target/maven-it/org/openrewrite/maven/RewriteRunIT/plaintext_masks/project/src/main/java/sample/in-src.ext by:",
                        "Changes have been made to target/maven-it/org/openrewrite/maven/RewriteRunIT/plaintext_masks/project/.in-root by:",
                        "Changes have been made to target/maven-it/org/openrewrite/maven/RewriteRunIT/plaintext_masks/project/from-default-list.py by:",
                        "Changes have been made to target/maven-it/org/openrewrite/maven/RewriteRunIT/plaintext_masks/project/src/main/java/sample/Dummy.java by:"
                )
                .doesNotContain("in-root.ignored");
    }

}
