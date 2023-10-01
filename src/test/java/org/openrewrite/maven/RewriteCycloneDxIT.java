/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
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

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenOption(MavenCLIOptions.NO_TRANSFER_PROGRESS)
@MavenOption(MavenCLIExtra.MUTE_PLUGIN_VALIDATION_WARNING)
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:cyclonedx")
@SuppressWarnings("NewClassNamingConvention")
class RewriteCycloneDxIT {

    @MavenTest
    @Disabled("module b consistently fails to resolve the locally-built artifact a due to aether resolution errors")
    void multi_module_with_cross_module_dependencies(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .project()
                .hasTarget()
                .withFile("multi_module_with_cross_module_dependencies-1.0-cyclonedx.xml")
                .exists();

        assertThat(result)
                .project()
                .withModule("a")
                .hasTarget()
                .withFile("a-1.0-cyclonedx.xml")
                .exists();

        assertThat(result)
                .project()
                .withModule("b")
                .hasTarget()
                .withFile("b-1.0-cyclonedx.xml")
                .exists();

        assertThat(result).out().warn().isEmpty();
    }

    @MavenTest
    void multi_module_with_independent_modules(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .project()
                .hasTarget()
                .withFile("multi_module_with_independent_modules-1.0-cyclonedx.xml")
                .exists();

        assertThat(result)
                .project()
                .withModule("a")
                .hasTarget()
                .withFile("a-1.0-cyclonedx.xml")
                .exists();

        assertThat(result)
                .project()
                .withModule("b")
                .hasTarget()
                .withFile("b-1.0-cyclonedx.xml")
                .exists();

        // ignore Maven POM cache warning, it may appear on some systems
        // https://github.com/openrewrite/rewrite-maven-plugin/issues/636
        assertThat(result).out().warn()
                .filteredOn(warn -> !"Unable to initialize RocksdbMavenPomCache, falling back to InMemoryMavenPomCache"
                        .equals(warn))
                .isEmpty();
    }

    @MavenTest
    void single_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .project()
                .hasTarget()
                .withFile("single_project-1.0-cyclonedx.xml")
                .exists();

        assertThat(result).out().warn().isEmpty();
    }


}
