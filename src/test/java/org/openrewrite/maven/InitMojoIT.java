package org.openrewrite.maven;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.extension.SystemProperties;
import com.soebes.itf.jupiter.extension.SystemProperty;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

@MavenJupiterExtension
@MavenGoal("${project.groupId}:${project.artifactId}:${project.version}:init")
@SystemProperties({
        @SystemProperty(value = "activeRecipes", content = "org.openrewrite.java.testing.junit5.ParameterizedRunnerToParameterized"),
        @SystemProperty(value = "dependencies", content = "<dependencies><dependency><groupId>org.openrewrite.recipe</groupId><artifactId>rewrite-spring</artifactId><version>4.14.1</version></dependency></dependencies>"),
        @SystemProperty(value = "rootOnly", content = "false")
})
public class InitMojoIT {

    @MavenTest
    void single_project(MavenExecutionResult result) {
        assertThat(result)
                .isSuccessful()
                .out()
                .info()
                .matches(logLines -> logLines.stream()
                        .anyMatch(logLine -> logLine.contains("Added rewrite-maven-plugin to")),
                        "Logs success message");
    }

}
