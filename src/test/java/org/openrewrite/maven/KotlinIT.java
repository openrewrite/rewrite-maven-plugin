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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenOption(MavenCLIOptions.NO_TRANSFER_PROGRESS)
@MavenOption(MavenCLIExtra.MUTE_PLUGIN_VALIDATION_WARNING)
@MavenOption(MavenCLIOptions.VERBOSE)
@DisabledOnOs(OS.WINDOWS)
@MavenGoal("install")
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:run")
class KotlinIT {
    @MavenTest
    void basic_kotlin_project(MavenExecutionResult result) {
        assertThat(result)
          .isSuccessful()
          .out()
          .debug()
          .anySatisfy(line -> assertThat(line).contains("Scanned 1 kotlin source files in main scope."))
          .anySatisfy(line -> assertThat(line).contains("Scanned 1 kotlin source files in test scope."))
          .anySatisfy(line -> assertThat(line).contains("org.openrewrite.kotlin.format.AutoFormat"));
    }
}
