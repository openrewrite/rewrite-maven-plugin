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

import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.marker.JavaVersion;

import java.nio.charset.Charset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * @author Fabian Krüger
 */
class MavenMojoProjectParserTest {
    @DisplayName("Given No Java version information exists in Maven Then java.specification.version should be used")
    @Test
    void givenNoJavaVersionInformationExistsInMavenThenJavaSpecificationVersionShouldBeUsed() {
        JavaVersion marker = MavenMojoProjectParser.getSrcTestJavaVersion(new MavenProject());
        assertThat(marker.getSourceCompatibility()).isEqualTo(System.getProperty("java.specification.version"));
        assertThat(marker.getTargetCompatibility()).isEqualTo(System.getProperty("java.specification.version"));
    }

    @DisplayName("getCharset should not throw when encoding is a property placeholder")
    @Test
    void getCharsetShouldNotThrowWhenEncodingIsPropertyPlaceholder() {
        MavenProject mavenProject = new MavenProject();

        // Set up maven-compiler-plugin with encoding as property placeholder
        Plugin compilerPlugin = new Plugin();
        compilerPlugin.setGroupId("org.apache.maven.plugins");
        compilerPlugin.setArtifactId("maven-compiler-plugin");

        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom encoding = new Xpp3Dom("encoding");
        encoding.setValue("${java.encoding}");
        configuration.addChild(encoding);
        compilerPlugin.setConfiguration(configuration);

        Build build = new Build();
        build.addPlugin(compilerPlugin);
        mavenProject.setBuild(build);

        // Should not throw IllegalCharsetNameException
        assertThatCode(() -> MavenMojoProjectParser.getCharset(mavenProject))
                .doesNotThrowAnyException();

        // Should return empty when encoding is unresolved placeholder
        Optional<Charset> charset = MavenMojoProjectParser.getCharset(mavenProject);
        assertThat(charset).isEmpty();
    }

    @DisplayName("getCharset should not throw when project.build.sourceEncoding is a property placeholder")
    @Test
    void getCharsetShouldNotThrowWhenSourceEncodingIsPropertyPlaceholder() {
        MavenProject mavenProject = new MavenProject();
        mavenProject.setBuild(new Build());
        mavenProject.getProperties().setProperty("project.build.sourceEncoding", "${java.encoding}");

        // Should not throw IllegalCharsetNameException
        assertThatCode(() -> MavenMojoProjectParser.getCharset(mavenProject))
                .doesNotThrowAnyException();

        // Should return empty when encoding is unresolved placeholder
        Optional<Charset> charset = MavenMojoProjectParser.getCharset(mavenProject);
        assertThat(charset).isEmpty();
    }

    @DisplayName("getCharset should return charset when encoding is valid")
    @Test
    void getCharsetShouldReturnCharsetWhenEncodingIsValid() {
        MavenProject mavenProject = new MavenProject();

        // Set up maven-compiler-plugin with valid encoding
        Plugin compilerPlugin = new Plugin();
        compilerPlugin.setGroupId("org.apache.maven.plugins");
        compilerPlugin.setArtifactId("maven-compiler-plugin");

        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom encoding = new Xpp3Dom("encoding");
        encoding.setValue("UTF-8");
        configuration.addChild(encoding);
        compilerPlugin.setConfiguration(configuration);

        Build build = new Build();
        build.addPlugin(compilerPlugin);
        mavenProject.setBuild(build);

        Optional<Charset> charset = MavenMojoProjectParser.getCharset(mavenProject);
        assertThat(charset).isPresent();
        assertThat(charset.get().name()).isEqualTo("UTF-8");
    }
}
