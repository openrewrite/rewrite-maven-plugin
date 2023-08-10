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
package org.openrewrite.maven.ux;

import org.apache.maven.plugin.logging.Log;
import org.openrewrite.NoopProgressBar;
import org.openrewrite.ProgressBar;
import org.openrewrite.internal.lang.Nullable;

public class MavenLogProgressBar extends NoopProgressBar {

    private final Log log;

    public MavenLogProgressBar(Log log) {
        this.log = log;
    }

    @Override
    public ProgressBar setExtraMessage(String extraMessage) {
        log.info(extraMessage);
        return this;
    }

    @Override
    public void intermediateResult(@Nullable String message) {
        log.debug(message);
    }

    @Override
    public void finish(String message) {
        log.info(message);
    }

}
