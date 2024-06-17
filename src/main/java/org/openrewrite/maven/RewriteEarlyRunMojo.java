/*
 * Copyright 2024 the original author or authors.
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

import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Run the configured recipes and apply the changes locally.
 * <p>
 * This variant of rewrite:run will fork the maven life cycle and can be run as a "stand-alone" goal.
 * It will execute the maven build up to the process-resources phase.
 * <p>
 * This goal is useful when there is a need to run recipes on top of non-buildable project.
 */
@Mojo(name = "earlyRun", threadSafe = true, defaultPhase = LifecyclePhase.PROCESS_RESOURCES)
@Execute(phase = LifecyclePhase.PROCESS_RESOURCES)
public class RewriteEarlyRunMojo extends AbstractRewriteRunMojo {
}
