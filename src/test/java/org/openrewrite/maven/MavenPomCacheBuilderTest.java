/*
 * Copyright 2026 the original author or authors.
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

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.maven.cache.InMemoryMavenPomCache;
import org.openrewrite.maven.cache.MavenPomCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MavenPomCacheBuilderTest {

    @Test
    void fallsBackToInMemoryCacheWhenRocksdbFails(@TempDir Path workspace) throws IOException {
        // given a workspace where Rocksdb init is forced to fail
        // (a regular file where RocksdbMavenPomCache expects to create its ".rewrite-cache" directory)
        Files.createFile(workspace.resolve(".rewrite-cache"));

        // when
        MavenPomCache cache = new MavenPomCacheBuilder(new SystemStreamLog()).build(workspace.toString());

        // then build() owns the fallback and returns an InMemory cache rather than null
        assertThat(cache).isInstanceOf(InMemoryMavenPomCache.class);
    }
}
