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
package org.openrewrite.maven.jupiter.extension;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.openrewrite.jgit.api.Git;

import java.lang.reflect.Method;
import java.nio.file.Path;

class GitITExtension implements BeforeEachCallback {
    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        Class<?> testClass = context.getTestClass()
          .orElseThrow(() -> new ExtensionConfigurationException("MavenITExtension is only supported for classes."));
        Method methodName = context.getTestMethod().orElseThrow(() -> new IllegalStateException("No method given"));

        Path targetTestClassesDirectory = getTargetDir().resolve("maven-it");
        String toFullyQualifiedPath = toFullyQualifiedPath(testClass);

        Path mavenItTestCaseDirectory = targetTestClassesDirectory.resolve(toFullyQualifiedPath).resolve(methodName.getName());
        Git.init().setDirectory(mavenItTestCaseDirectory.toFile()).call().close();
    }

    static Path getMavenBaseDir() {
        return Path.of(System.getProperty("basedir", System.getProperty("user.dir", ".")));
    }

    static String toFullyQualifiedPath(Class<?> testClass) {
        return testClass.getCanonicalName().replace('.', '/');
    }

    /**
     * @return the target directory of the current project.
     */
    static Path getTargetDir() {
        return getMavenBaseDir().resolve("target");
    }
}
