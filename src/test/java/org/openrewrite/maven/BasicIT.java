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
import org.openrewrite.maven.jupiter.extension.GitJupiterExtension;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@GitJupiterExtension
@MavenJupiterExtension
@MavenOption(MavenCLIOptions.NO_TRANSFER_PROGRESS)
@MavenOption(MavenCLIExtra.MUTE_PLUGIN_VALIDATION_WARNING)
class BasicIT {

    @MavenGoal("clean")
    @MavenGoal("package")
    @MavenTest
    void groupid_artifactid_should_be_ok(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .containsOnly("JAR will be empty - no content was marked for inclusion!");
    }

    @Disabled
    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
    @MavenOption(value = MavenCLIOptions.SETTINGS, parameter = "settings.xml")
    @MavenTest
    void null_check_profile_activation(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .info()
                .anySatisfy(line -> assertThat(line).contains("Applying recipes would make no changes. No patch file generated."));

        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                // Ignore warning logged on Mac OS X; https://github.com/openrewrite/rewrite-maven-plugin/issues/506
                .filteredOn(warn -> !"Unable to initialize RocksdbMavenPomCache, falling back to InMemoryMavenPomCache".equals(warn))
                .isEmpty();
    }

    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
    @MavenTest
    @SystemProperty(value = "ossrh_snapshots_url", content = "https://central.sonatype.com/repository/maven-snapshots")
    void resolves_maven_properties_from_user_provided_system_properties(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .allSatisfy(line -> assertThat(line).doesNotContain("Invalid repository URL ${ossrh_snapshots_url}"))
                .allSatisfy(line -> assertThat(line).doesNotContain("Unable to resolve property ${ossrh_snapshots_url}"));
    }

    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
    @MavenOption(value = MavenCLIOptions.SETTINGS, parameter = "settings-user.xml")
    @MavenProfile("example_profile_id")
    @MavenTest
    @SystemProperty(value = "REPOSITORY_URL", content = "https://maven-eu.nuxeo.org/nexus/content/repositories/public/")
    void resolves_settings(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .plain()
                .allSatisfy(line -> assertThat(line).doesNotContain("Illegal character in path at index 1"));
    }

    @MavenGoal("clean")
    @MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:dryRun")
    @MavenTest
    void snapshot_ok(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .warn()
                .isEmpty();
        assertThat(result).out().info().contains("Running recipe(s)...");
    }

}
