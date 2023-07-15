/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProjectHelper;
import org.openrewrite.ExecutionContext;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.maven.utilities.PrintMavenAsCycloneDxBom;
import org.openrewrite.xml.tree.Xml;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/**
 * Generate a CycloneDx bill of materials outlining the project's dependencies, including transitive dependencies.
 */
@Mojo(name = "cyclonedx", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true,
        defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
@SuppressWarnings("unused")
public class CycloneDxBomMojo extends AbstractRewriteMojo {

    @Component
    private MavenProjectHelper projectHelper;

    @Override
    public void execute() throws MojoExecutionException {
        ExecutionContext ctx = executionContext();
        Path baseDir = getBuildRoot();
        Xml.Document maven = new MavenMojoProjectParser(getLog(), baseDir, pomCacheEnabled, pomCacheDirectory, runtime, skipMavenParsing, getExclusions(), getPlainTextMasks(), sizeThresholdMb, mavenSession, settingsDecrypter, runPerSubmodule).parseMaven(project, Collections.emptyList(), ctx);
        if (maven != null) {
            File cycloneDxBom = buildCycloneDxBom(maven);
            projectHelper.attachArtifact(project, "xml", "cyclonedx", cycloneDxBom);
        } else {
            getLog().error("No cyclonedx bom produced");
        }
    }

    @Nullable
    private File buildCycloneDxBom(Xml.Document pomAst) {
        try {
            File cycloneDxBom = new File(project.getBuild().getDirectory(),
                    project.getArtifactId() + "-" + project.getVersion() + "-cyclonedx.xml");

            //noinspection ResultOfMethodCallIgnored
            cycloneDxBom.getParentFile().mkdirs();

            Files.write(cycloneDxBom.toPath(), PrintMavenAsCycloneDxBom.print(pomAst)
                    .getBytes(pomAst.getCharset() == null ?
                            StandardCharsets.UTF_8 : pomAst.getCharset()));

            return cycloneDxBom;
        } catch (Throwable t) {
            getLog().warn("Unable to produce CycloneDX BOM", t);
            return null;
        }
    }
}
