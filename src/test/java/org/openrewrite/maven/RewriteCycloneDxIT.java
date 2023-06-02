package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.*;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import org.junit.jupiter.api.Disabled;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

        assertThat(result).out().warn().isEmpty();
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

    @MavenTest
    void correct_version_of_netty_handler_should_be_reported_in_selenese_runner_java_3e84e8e(MavenExecutionResult result) throws IOException, URISyntaxException {
        assertThat(result)
                .isSuccessful()
                .project()
                .hasTarget()
                .withFile("selenese-runner-java-4.2.0-cyclonedx.xml")
                .exists();

        Path seleneseRunnerJavaSBOM = Paths.get("target/maven-it/org/openrewrite/maven/RewriteCycloneDxIT/correct_version_of_netty_handler_should_be_reported_in_selenese_runner_java_3e84e8e/project/target/selenese-runner-java-4.2.0-cyclonedx.xml");
        byte[] xmlBytes = Files.readAllBytes(seleneseRunnerJavaSBOM);
        String sbomContents = new String(xmlBytes, Charset.defaultCharset());


        String expectedString = "        <component bom-ref=\"pkg:maven/io.netty/netty-handler@4.1.79.Final?type=jar\" type=\"library\">\n" +
                "            <group>io.netty</group>\n" +
                "            <name>netty-handler</name>\n" +
                "            <version>4.1.79.Final</version>\n" +
                "            <scope>required</scope>\n" +
                "            <purl>pkg:maven/io.netty/netty-handler@4.1.79.Final?type=jar</purl>\n" +
                "        </component>";

        assertThat(sbomContents).contains(expectedString);

    }
}
