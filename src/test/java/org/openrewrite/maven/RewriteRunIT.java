package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import org.junit.jupiter.api.Disabled;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:run")
public class RewriteRunIT {

    @MavenTest
    void single_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful();
    }

    @MavenTest
    void multi_module_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful();
    }


}
