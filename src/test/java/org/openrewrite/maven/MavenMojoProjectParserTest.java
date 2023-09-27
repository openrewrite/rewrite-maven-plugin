package org.openrewrite.maven;

import it.unimi.dsi.fastutil.ints.IntSets;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.internal.DefaultRuntimeInformation;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.marker.Marker;
import org.openrewrite.tree.ParsingEventListener;
import org.openrewrite.tree.ParsingExecutionContextView;
import org.openrewrite.xml.tree.Xml;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchIllegalArgumentException;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Fabian Krüger
 */
class MavenMojoProjectParserTest {
    @Test
    @DisplayName("Given No Java version information exists in Maven Then java.specification.version should be used")
    void givenNoJavaVersionInformationExistsInMavenThenJavaSpecificationVersionShouldBeUsed() {
        MavenMojoProjectParser sut = new MavenMojoProjectParser(new SystemStreamLog(), null, false, null, new DefaultRuntimeInformation(), false, Collections.EMPTY_LIST, Collections.EMPTY_LIST, -1, null, null, false);
        List<Marker> markers = sut.generateProvenance(new MavenProject());
        JavaVersion marker = markers.stream().filter(JavaVersion.class::isInstance).map(JavaVersion.class::cast).findFirst().get();
        assertThat(marker.getSourceCompatibility()).isEqualTo(System.getProperty("java.specification.version"));
        assertThat(marker.getTargetCompatibility()).isEqualTo(System.getProperty("java.specification.version"));
    }

    @Test
    @DisplayName("order of build files should reflect reactor build order")
    void orderOfBuildFilesShouldReflectReactorBuildOrder(@TempDir Path tempDir) throws NoSuchMethodException, IOException {
        MavenMojoProjectParser sut = new MavenMojoProjectParser(new SystemStreamLog(), null, false, null, new DefaultRuntimeInformation(), false, Collections.EMPTY_LIST, Collections.EMPTY_LIST, -1, null, null, false);
        /*Method collectPoms = MavenMojoProjectParser.class.getDeclaredMethod("collectPoms", MavenProject.class, Set.class);
        collectPoms.setAccessible(true);
        Set<Path> allPoms = new LinkedHashSet<>();
        Object mavenProjects;
        collectPoms.invoke(sut, mavenProjects, allPoms);*/
        String pomXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                "    <modelVersion>4.0.0</modelVersion>\n" +
                "    <groupId>com.example</groupId>\n" +
                "    <artifactId>multi-module-1</artifactId>\n" +
                "    <version>1.0.0</version>\n" +
                "    <packaging>pom</packaging>\n" +
                "    <properties>\n" +
                "        <maven.compiler.target>17</maven.compiler.target>\n" +
                "        <maven.compiler.source>17</maven.compiler.source>\n" +
                "        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n" +
                "    </properties>\n" +
                "    <dependencies>\n" +
                "        <dependency>\n" +
                "            <groupId>org.springframework.boot</groupId>\n" +
                "            <artifactId>spring-boot-starter</artifactId>\n" +
                "            <version>3.1.1</version>\n" +
                "        </dependency>\n" +
                "    </dependencies>\n" +
                "    <modules>\n" +
                "        <module>module-a</module>\n" +
                "        <module>module-b</module>\n" +
                "    </modules>\n" +
                "</project>";

        String moduleAPom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                "    <modelVersion>4.0.0</modelVersion>\n" +
                "    <parent>\n" +
                "        <groupId>com.example</groupId>\n" +
                "        <artifactId>multi-module-1</artifactId>\n" +
                "        <version>1.0.0</version>\n" +
                "    </parent>\n" +
                "    <artifactId>module-a</artifactId>\n" +
                "    <dependencies>\n" +
                "        <dependency>\n" +
                "            <groupId>com.example</groupId>\n" +
                "            <artifactId>module-b</artifactId>\n" +
                "            <version>${project.version}</version>\n" +
                "        </dependency>\n" +
                "    </dependencies>\n" +
                "</project>";

        String moduleBPom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                "    <modelVersion>4.0.0</modelVersion>\n" +
                "    <parent>\n" +
                "        <groupId>com.example</groupId>\n" +
                "        <artifactId>multi-module-1</artifactId>\n" +
                "        <version>1.0.0</version>\n" +
                "    </parent>\n" +
                "    <artifactId>module-b</artifactId>\n" +
                "</project>";


        Path rootPomFile = tempDir.resolve("pom.xml");
        Files.write(rootPomFile, pomXml.getBytes());

        Path aPath = tempDir.resolve("module-a");
        aPath.toFile().mkdirs();
        Path aFile = aPath.resolve("pom.xml");
        Files.write(aFile, moduleAPom.getBytes());

        Path bPath = tempDir.resolve("module-b");
        Path bFile = bPath.resolve("pom.xml");
        bPath.toFile().mkdirs();
        Files.write(bFile, moduleBPom.getBytes());

        MavenProject rootProject = new MavenProject();// mock(MavenProject.class);
        MavenProject aProject = new MavenProject();
        MavenProject bProject = new MavenProject();

        List<MavenProject> mavenProjects = new ArrayList<>();
        mavenProjects.add(bProject);
        mavenProjects.add(rootProject);
        mavenProjects.add(aProject);
        Map<MavenProject, List<Marker>> markers = new LinkedHashMap<>();
        ExecutionContext ctx = new InMemoryExecutionContext(Assertions::fail);
        List<String> capturedResourcePaths = new ArrayList<>();
        new ParsingExecutionContextView(ctx).setParsingListener((input, sourceFile) -> {
            capturedResourcePaths.add(sourceFile.getSourcePath().toString());
        });
//        Map<MavenProject, Xml.Document> mavenProjectDocumentMap = sut.parseMaven(mavenProjects, markers, ctx);
        MavenProject mavenProject = new MavenProject();
        mavenProject.setFile(rootPomFile.toFile());
        sut.parseMaven(mavenProject, new ArrayList<>(), ctx);

        assertThat(capturedResourcePaths.get(0)).isEqualTo("pom.xml");
        assertThat(capturedResourcePaths.get(1)).isEqualTo("module-b/pom.xml");
        assertThat(capturedResourcePaths.get(2)).isEqualTo("module-a/pom.xml");
    }
}