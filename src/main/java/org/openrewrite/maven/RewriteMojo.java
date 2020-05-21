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

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.micrometer.prometheus.rsocket.PrometheusRSocketClient;
import io.prometheus.client.CollectorRegistry;
import io.rsocket.transport.ClientTransport;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.transport.netty.client.WebsocketClientTransport;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.openrewrite.Change;
import org.openrewrite.checkstyle.RewriteCheckstyle;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.TcpClient;
import reactor.util.retry.Retry;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.rsocket.transport.netty.UriUtils.getPort;
import static java.util.stream.Collectors.toList;

// https://medium.com/swlh/step-by-step-guide-to-developing-a-custom-maven-plugin-b6e3a0e09966
// https://carlosvin.github.io/posts/creating-custom-maven-plugin/en/#_dependency_injection
// https://developer.okta.com/blog/2019/09/23/tutorial-build-a-maven-plugin
@Mojo(name = "checkstyle", requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
@Execute(phase = LifecyclePhase.PROCESS_SOURCES)
public class RewriteMojo extends AbstractMojo {
    // from AbstractCheckstyleReport default and config
    private static final String DEFAULT_CONFIG_LOCATION = "sun_checks.xml";

    @Parameter(property = "configLocation", defaultValue = DEFAULT_CONFIG_LOCATION)
    protected String configLocation;

    @Parameter(property = "reportOutputDirectory", defaultValue = "${project.reporting.outputDirectory}/rewrite", required = true)
    private File reportOutputDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "excludes")
    private Set<String> excludes;

    @Parameter(property = "action", defaultValue = "FIX")
    private RewriteAction action;

    @Parameter(property = "metricsUri")
    private String metricsUri;

    @Parameter(property = "metricsUsername")
    private String metricsUsername;

    @Parameter(property = "metricsPassword")
    private String metricsPassword;

    private PrometheusRSocketClient metricsClient;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // when we need dependencies for other rules
//        List<Path> dependencies = project.getArtifacts().stream()
//                .map(d -> d.getFile().toPath())
//                .collect(Collectors.toList());

        MeterRegistry meterRegistry = getMeterRegistry();

        File checkstyleConfig = new File(configLocation);

        try {
            if (checkstyleConfig.exists()) {
                try (FileInputStream checkstyleIn = new FileInputStream(checkstyleConfig)) {
                    RewriteCheckstyle rewriteCheckstyle = new RewriteCheckstyle(checkstyleIn, excludes == null ? Collections.emptySet() : excludes, null);

                    rewriteSourceSet(meterRegistry, rewriteCheckstyle, project.getBuild().getSourceDirectory());
                    rewriteSourceSet(meterRegistry, rewriteCheckstyle, project.getBuild().getTestSourceDirectory());

                    if (metricsClient != null) {
                        try {
                            // Don't bother blocking long here. If the build ends before the dying push can happen, so be it.
                            metricsClient.pushAndClose().block(Duration.ofSeconds(3));
                        } catch (Throwable ignore) {
                            // sometimes fails when connection already closed, e.g. due to flaky internet connection
                        }
                    }
                } catch (IOException e) {
                    throw new MojoFailureException("Unable to read checkstyle configuration", e);
                }
            }
        } finally {
            meterRegistry.close();
        }
    }

    private void rewriteSourceSet(MeterRegistry meterRegistry, RewriteCheckstyle rewriteCheckstyle, String sourceDirectory) throws IOException {
        File sourceDirectoryFile = new File(sourceDirectory);
        if (!sourceDirectoryFile.exists()) {
            return;
        }

        Path sourceRoot = sourceDirectoryFile.toPath();
        List<Path> sources = Files.walk(sourceRoot)
                .filter(f -> !Files.isDirectory(f) && f.toFile().getName().endsWith(".java"))
                .collect(Collectors.toList());

        getLog().info("Total sources: " + sources.size());

        List<J.CompilationUnit> cus = new JavaParser()
                .setLogCompilationWarningsAndErrors(false)
                .setMeterRegistry(meterRegistry)
                .parse(sources, project.getBasedir().toPath());

        List<Change<J.CompilationUnit>> changes = cus.stream()
                .map(cu -> rewriteCheckstyle.apply(cu.refactor().setMeterRegistry(meterRegistry)).fix())
                .filter(change -> !change.getRulesThatMadeChanges().isEmpty())
                .collect(toList());

        if (!changes.isEmpty()) {
            for (Change<J.CompilationUnit> change : changes) {
                String actionTaken = action.equals(RewriteAction.WARN_ONLY) ?
                        "need to be fixed" : "have been fixed";
                getLog().warn("The following checkstyle rules in " +
                        change.getOriginal().getSourcePath() +
                        " " + actionTaken + ":");
                for (String rule : change.getRulesThatMadeChanges()) {
                    getLog().warn("   " + rule);
                }
            }

            reportOutputDirectory.mkdirs();
            try (BufferedWriter writer = Files.newBufferedWriter(reportOutputDirectory.toPath().resolve("rewrite.patch"))) {
                for (Change<J.CompilationUnit> change : changes) {
                    writer.write(change.diff() + "\n");
                    if (action.compareTo(RewriteAction.FIX) >= 0) {
                        try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                                project.getBasedir().toPath().resolve(change.getOriginal().getSourcePath()))) {
                            sourceFileWriter.write(change.getFixed().print());
                        }
                    }
                }
            }
        }
    }

    private MeterRegistry getMeterRegistry() {
        if (metricsUri == null) {
            return new CompositeMeterRegistry();
        } else if (metricsUri.equals("LOG")) {
            return new MavenLoggingMeterRegistry(getLog());
        } else {
            try {
                URI uri = URI.create(metricsUri);
                PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, new CollectorRegistry(), Clock.SYSTEM);

                ClientTransport clientTransport;
                switch (uri.getScheme()) {
                    case "ephemeral":
                    case "https":
                    case "wss": {
                        TcpClient tcpClient = TcpClient.create().secure().host(uri.getHost()).port(getPort(uri, 443));
                        clientTransport = getWebsocketClientTransport(tcpClient);
                        break;
                    }
                    case "http":
                    case "ws": {
                        TcpClient tcpClient = TcpClient.create().host(uri.getHost()).port(getPort(uri, 80));
                        clientTransport = getWebsocketClientTransport(tcpClient);
                        break;
                    }
                    case "tcp":
                        clientTransport = TcpClientTransport.create(uri.getHost(), uri.getPort());
                        break;
                    default:
                        getLog().warn("Unable to publish metrics. Unrecognized scheme " + uri.getScheme());
                        return new CompositeMeterRegistry();
                }

                metricsClient = PrometheusRSocketClient
                        .build(registry, registry::scrape, clientTransport)
                        .retry(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(3)))
                        .connect();

                return registry;
            } catch (Throwable t) {
                getLog().warn("Unable to publish metrics", t);
            }
        }

        return new CompositeMeterRegistry();
    }

    private ClientTransport getWebsocketClientTransport(TcpClient tcpClient) {
        HttpClient httpClient = HttpClient.from(tcpClient).wiretap(true);
        if (metricsUsername != null && metricsPassword != null) {
            httpClient = httpClient.headers(h -> h.add("Authorization", "Basic: " + Base64.getUrlEncoder()
                    .encodeToString((metricsUsername + ":" + metricsPassword).getBytes())));
        }
        return WebsocketClientTransport.create(httpClient, "/");
    }
}
