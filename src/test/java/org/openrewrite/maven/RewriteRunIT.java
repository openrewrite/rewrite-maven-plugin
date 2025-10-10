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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.openrewrite.maven.jupiter.extension.GitJupiterExtension;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;
import static org.openrewrite.PathUtils.separatorsToSystem;

@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:run")
@GitJupiterExtension
@MavenJupiterExtension
@MavenOption(MavenCLIOptions.NO_TRANSFER_PROGRESS)
@MavenOption(MavenCLIExtra.MUTE_PLUGIN_VALIDATION_WARNING)
class RewriteRunIT {

    @MavenTest
    void multi_module_project(MavenExecutionResult result) {
        assertThat(result)
          .isSuccessful()
          .out()
          .warn()
          .anySatisfy(line -> assertThat(line).contains("org.openrewrite.staticanalysis.SimplifyBooleanExpression"));
    }

    @MavenGoal("generate-test-sources")
    @MavenTest
    @SystemProperties({
      @SystemProperty(value = "rewrite.activeRecipes", content = "org.openrewrite.java.search.FindTypes"),
      @SystemProperty(value = "rewrite.options", content = "fullyQualifiedTypeName=org.junit.jupiter.api.Test")
    })
    void multi_source_sets_project(MavenExecutionResult result) {
        assertThat(result)
          .isSuccessful()
          .out()
          .warn()
          .contains("Changes have been made to %s by:".formatted(separatorsToSystem("project/src/integration-test/java/sample/IntegrationTest.java")))
          .contains("Changes have been made to %s by:".formatted(separatorsToSystem("project/src/test/java/sample/RegularTest.java")));
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
          .anySatisfy(line -> assertThat(line).contains(separatorsToSystem("/sample/ThrowingRecipe.java"), "This recipe throws an exception"));
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
          .contains("Changes have been made to %s by:".formatted(separatorsToSystem("project/pom.xml")))
          .contains("    org.openrewrite.maven.RemovePlugin: {groupId=org.openrewrite.maven, artifactId=rewrite-maven-plugin}");
        assertThat(result.getMavenProjectResult().getModel().getBuild()).isNull();
    }

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Quotes for comment are removed during execution")
    @SystemProperties({
      @SystemProperty(value = "rewrite.activeRecipes", content = "org.openrewrite.java.AddCommentToMethod"),
      @SystemProperty(value = "rewrite.options", content = "comment='{\"test\":{\"some\":\"yeah\"}}',methodPattern=sample.SomeClass doTheThing(..)")
    })
    @MavenTest
    void command_line_options_json(MavenExecutionResult result) {
        assertThat(result)
          .isSuccessful()
          .out()
          .warn()
          .contains("Changes have been made to %s by:".formatted(separatorsToSystem("project/src/main/java/sample/SomeClass.java")))
          .contains("    org.openrewrite.java.AddCommentToMethod: {comment='{\"test\":{\"some\":\"yeah\"}}', methodPattern=sample.SomeClass doTheThing(..)}");
    }

    @Disabled("We should implement a simpler test to make sure that regular markers don't get added to source files")
    @MavenTest
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
    void container_masks(MavenExecutionResult result) {
        assertThat(result)
          .isSuccessful()
          .out()
          .warn()
          .contains(
                "Changes have been made to %s by:".formatted(separatorsToSystem("project/containerfile.build")),
                "Changes have been made to %s by:".formatted(separatorsToSystem("project/Dockerfile")),
                "Changes have been made to %s by:".formatted(separatorsToSystem("project/Containerfile")),
                "Changes have been made to %s by:".formatted(separatorsToSystem("project/build.dockerfile"))
          );
    }

    @MavenTest
    @SystemProperty(value = "rewrite.additionalPlainTextMasks", content = "**/*.ext,**/.in-root")
    void plaintext_masks(MavenExecutionResult result) {
        assertThat(result)
          .isSuccessful()
          .out()
          .warn()
          .contains(
                  "Changes have been made to %s by:".formatted(separatorsToSystem("project/src/main/java/sample/in-src.ext")),
                  "Changes have been made to %s by:".formatted(separatorsToSystem("project/.in-root")),
                  "Changes have been made to %s by:".formatted(separatorsToSystem("project/from-default-list.py")),
                  "Changes have been made to %s by:".formatted(separatorsToSystem("project/src/main/java/sample/Dummy.java"))
          )
          .doesNotContain("in-root.ignored");
    }

}
