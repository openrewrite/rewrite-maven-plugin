/*
 * Copyright 2021 the original author or authors.
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

import org.apache.maven.plugin.logging.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MavenOptsHelper {

    public static void checkAndLogMissingJvmModuleExports(Log log) {
        try {
            String version = System.getProperty("java.version");
            int dot = version.indexOf(".");
            String majorVersionString = dot > 0 ? version.substring(0, dot) : version;
            int majorVersion = Integer.parseInt(majorVersionString);
            if (majorVersion > 15) {
                String mavenOpts = System.getenv().getOrDefault("MAVEN_OPTS", "");
                Pattern pattern = Pattern.compile("--add-exports\\sjdk\\.compiler/com\\.sun\\.tools\\.javac\\.(\\w+)=ALL-UNNAMED");
                Matcher matcher = pattern.matcher(mavenOpts);
                List<String> requiredExportPackages = Arrays.asList("code", "comp", "file", "jvm", "main", "model", "processing", "tree", "util");
                Set<String> exportedPackages = new HashSet<>(requiredExportPackages);
                while (matcher.find()) {
                    String pkg = matcher.group(1);
                    exportedPackages.remove(pkg);
                }
                if (!exportedPackages.isEmpty()) {
                    StringBuilder errMessage = new StringBuilder("Java ").append(version).append(" protected module access not exported for:");
                    for (String missingModuleExport : exportedPackages) {
                        errMessage.append("\n\tcom.sun.tools.javac.").append(missingModuleExport);
                    }
                    log.error(errMessage);
                    log.warn("The following exports should be added to your MAVEN_OPTS environment variable.");
                    StringBuilder infoMessage = new StringBuilder();
                    for (String exp : requiredExportPackages) {
                        infoMessage.append("--add-exports jdk.compiler/com.sun.tools.javac.").append(exp).append("=ALL-UNNAMED");
                    }
                    log.info(infoMessage);
                    log.info("");
                }
            }
        } catch (Exception e) {
            log.error(e);
        }
    }
}
