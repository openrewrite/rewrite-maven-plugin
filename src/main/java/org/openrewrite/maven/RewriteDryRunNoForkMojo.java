/*
 * Copyright 2022 the original author or authors.
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

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Generate warnings to the console for any recipe that would make changes, but do not make changes.
 *
 * This variant of rewrite:dryRun will not fork the maven life cycle and can be used (along with other goals) without
 * triggering repeated life-cycle events. It will execute the maven build up to the process-test-classes phase.
 */
@Mojo(name = "dryRunNoFork", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true,
        defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class RewriteDryRunNoForkMojo extends AbstractRewriteDryRunMojo {
}
