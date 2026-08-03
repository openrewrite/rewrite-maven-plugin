/*
 * Copyright 2026 the original author or authors.
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

@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:discover")
@MavenJupiterExtension
@MavenOption(MavenCLIOptions.NO_TRANSFER_PROGRESS)
@MavenOption(MavenCLIExtra.MUTE_PLUGIN_VALIDATION_WARNING)
class StaleRecipeArtifactIT {

    @MavenTest
    @SystemProperty(value = "rewrite.recipeArtifactCoordinates", content = "org.openrewrite.recipe:rewrite-testing-frameworks:LATEST")
    void dynamic_recipe_version(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .anySatisfy(line -> assertThat(line).contains("org.openrewrite.recipe:rewrite-testing-frameworks:LATEST"))
                .anySatisfy(line -> assertThat(line).contains(CodeGenomeProjectWarning.CREDENTIALS_DOCS));
    }

    @MavenTest
    @SystemProperty(value = "rewrite.recipeArtifactCoordinates", content = "org.openrewrite.recipe:rewrite-testing-frameworks:3.42.1")
    void pinned_recipe_version(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .allSatisfy(line -> assertThat(line).doesNotContain(CodeGenomeProjectWarning.CREDENTIALS_DOCS));
    }
}
