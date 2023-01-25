package org.openrewrite.maven;

import org.apache.commons.io.IOUtils;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class AbstractRewriteMojoTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "rewrite.yml",
        "https://httpstat.us/200",
        "https://raw.githubusercontent.com/quarkusio/quarkus/main/jakarta/quarkus3.yml"
    })
    void configLocation(String loc, @TempDir Path temp) throws IOException {
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

        if(!loc.startsWith("http")) {
            Files.write(temp.resolve(loc), "rewrite".getBytes(StandardCharsets.UTF_8));
        }

        AbstractRewriteMojo.Config config = mojo.getConfig();
        assertThat(config).isNotNull();
        try (InputStream is = config.inputStream) {
            assertThat(IOUtils.readLines(is, StandardCharsets.UTF_8)).isNotEmpty();
        }
    }
}
