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
package org.openrewrite.maven;

final class MavenCLIExtra
{
    /**
     * User property to mute Maven 3.9.2+ "plugin validation" warnings.
     */
    static final String MUTE_PLUGIN_VALIDATION_WARNING = "-Dorg.slf4j.simpleLogger.log.org.apache.maven.plugin.internal.DefaultPluginValidationManager=off";
}
