/*
 * Copyright 2023 the original author or authors.
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

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.openrewrite.maven.internal.MavenXmlMapper;

import java.nio.file.Path;

@Mojo(name = "dumpMavenSettings", threadSafe = true, requiresProject = false, aggregator = true)
public class RewriteDumpMavenSettings extends AbstractRewriteMojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path baseDir = getBuildRoot();
        MavenMojoProjectParser projectParser = new MavenMojoProjectParser(getLog(), baseDir, pomCacheEnabled, pomCacheDirectory, runtime, skipMavenParsing, getExclusions(), getPlainTextMasks(), sizeThresholdMb, mavenSession, settingsDecrypter, runPerSubmodule);
        MavenSettings mavenSettings = projectParser.buildSettings();

        try {
            getLog().info("The effective Maven settings in use by rewrite-maven-plugin are:");
            getLog().info(MavenXmlMapper.writeMapper().writer().writeValueAsString(mavenSettings));
        } catch (JsonProcessingException e) {
            throw new MojoExecutionException("Failed to dump maven settings.", e);
        }
    }
}
