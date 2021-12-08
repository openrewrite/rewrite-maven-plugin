package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.*;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import org.junit.jupiter.api.Disabled;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenOption(MavenCLIOptions.NO_TRANSFER_PROGRESS)
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:cyclonedx")
@SuppressWarnings("NewClassNamingConvention")
public class RewriteCycloneDxIT {

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


}
