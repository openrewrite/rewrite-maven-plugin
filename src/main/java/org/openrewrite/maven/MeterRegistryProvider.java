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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.apache.maven.plugin.logging.Log;
import org.jspecify.annotations.Nullable;

public class MeterRegistryProvider implements AutoCloseable {
    private final Log log;

    @Nullable
    private final String uriString;

    @Nullable
    private MeterRegistry registry;

    public MeterRegistryProvider(Log log, @Nullable String uriString) {
        this.log = log;
        this.uriString = uriString;
    }

    public MeterRegistry registry() {
        if (this.registry == null) {
            this.registry = buildRegistry();
        }
        return this.registry;
    }

    private MeterRegistry buildRegistry() {
       if ("LOG".equals(uriString)) {
            return new MavenLoggingMeterRegistry(log);
        }

        return new CompositeMeterRegistry();
    }

    @Override
    public void close() {
        if (registry != null) {
            registry.close();
        }
    }
}
