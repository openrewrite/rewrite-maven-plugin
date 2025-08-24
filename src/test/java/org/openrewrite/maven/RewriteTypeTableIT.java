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

@MavenJupiterExtension
@MavenOption(MavenCLIOptions.NO_TRANSFER_PROGRESS)
@MavenOption(MavenCLIExtra.MUTE_PLUGIN_VALIDATION_WARNING)
@MavenProfile("create-typetable")
class RewriteTypeTableIT {

    @MavenTest
    void typetable_default(MavenExecutionResult result) {
        assertThat(result)
          .isSuccessful()
          .project().has("src/main/resources/META-INF/rewrite");
        assertThat(result).out().info()
          .matches(logLines -> logLines.stream().anyMatch(line -> line.contains("Wrote com.google.guava:guava:jar:33.3.1-jre")),
            "contains guava 33.3.1-jre")
          .matches(logLines -> logLines.stream().anyMatch(line -> line.contains("Wrote com.google.guava:guava:jar:32.0.0-jre")),
            "contains guava 32.0.0-jre")
          .matches(logLines -> logLines.stream().anyMatch(line -> line.contains("Wrote src/main/resources/META-INF/rewrite/classpath.tsv.gz")),
            "write classpath.tsv.gz");
        assertThat(result).out().error().isEmpty();
    }

}
