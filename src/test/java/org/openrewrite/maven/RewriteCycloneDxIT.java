package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import org.junit.jupiter.api.Disabled;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:cyclonedx")
public class RewriteCycloneDxIT {

    @MavenTest
    void single_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .project()
                .hasTarget()
                .withFile("single_project-1.0-cyclonedx.xml")
                .exists();
    }

    @MavenTest
    @Disabled("module b consistently fails to resolve the locally-built artifact a due to aether resolution errors")
    void multi_module_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .project()
                .hasTarget()
                .withFile("multi_module_project-1.0-cyclonedx.xml")
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


    }

}
