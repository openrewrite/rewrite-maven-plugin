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
import org.junit.jupiter.api.Nested;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenOption(MavenCLIOptions.NO_TRANSFER_PROGRESS)
@MavenOption(MavenCLIExtra.MUTE_PLUGIN_VALIDATION_WARNING)
@MavenGoal("clean")
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:discover")
class RewriteDiscoverIT {

    @Nested
    class RecipeLookup {

        @MavenTest
        @SystemProperty(value = "detail", content = "true")
        void rewrite_discover_detail(MavenExecutionResult result) {
            assertThat(result)
              .isSuccessful()
              .out()
              .info()
              .anySatisfy(line -> assertThat(line).contains("options"));

            assertThat(result).out().warn().isEmpty();
        }

        @MavenTest
        @SystemProperty(value = "recipe", content = "org.openrewrite.JAVA.format.AutoFormAT")
        void rewrite_discover_recipe_lookup_case_insensitive(MavenExecutionResult result) {
            assertThat(result)
              .isSuccessful()
              .out()
              .info()
              .anySatisfy(line -> assertThat(line).contains("org.openrewrite.java.format.AutoFormat"));
        }

        @MavenTest
        @SystemProperty(value = "recursion", content = "1")
        void rewrite_discover_recursion(MavenExecutionResult result) {
            assertThat(result)
              .isSuccessful()
              .out()
              .info()
              .anySatisfy(line -> assertThat(line).contains("recipeList"));

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
          .anySatisfy(line -> assertThat(line).contains("com.example.RewriteDiscoverIT.CodeCleanup"));

        assertThat(result).out().warn().isEmpty();
    }

    @MavenTest
    void rewrite_discover_multi_module(MavenExecutionResult result) {
        assertThat(result)
          .isSuccessful()
          .out()
          .info()
          .satisfiesOnlyOnce(line -> assertThat(line).contains(":discover"));

        assertThat(result).out().warn().isEmpty();
    }

}
