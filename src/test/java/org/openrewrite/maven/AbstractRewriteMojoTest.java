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

import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractRewriteMojoTest {

    @ParameterizedTest
    @ValueSource(strings = {"rewrite.yml"})
    void configLocation(String loc, @TempDir Path temp) throws Exception {
        AbstractRewriteMojo mojo = new AbstractRewriteMojo() {
            {
                configLocation = loc;
                project = new MavenProject();
                project.setFile(new File(temp.toFile(), "pom.xml"));
            }

            @Override
            public void execute() {
            }
        };

        if (!loc.startsWith("http")) {
            Files.writeString(temp.resolve(loc), "rewrite");
        }

        AbstractRewriteMojo.Config config = mojo.getConfig();
        assertThat(config).isNotNull();
        try (InputStream is = config.inputStream) {
            assertThat(is.readAllBytes()).isNotEmpty();
        }
    }
}
