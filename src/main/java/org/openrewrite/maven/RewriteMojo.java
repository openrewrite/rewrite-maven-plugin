package org.openrewrite.maven;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.logging.LoggingMeterRegistry;
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

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import static io.rsocket.transport.netty.UriUtils.getPort;
import static java.util.stream.Collectors.toList;

@Mojo(name = "checkstyle", requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
@Execute(phase = LifecyclePhase.PROCESS_SOURCES)
public class RewriteMojo extends AbstractMojo {
    // from AbstractCheckstyleReport default and config
    private static final String DEFAULT_CONFIG_LOCATION = "sun_checks.xml";

    @Parameter(property = "checkstyle.config.location", defaultValue = DEFAULT_CONFIG_LOCATION)
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
        if (checkstyleConfig.exists()) {
            try (FileInputStream checkstyleIn = new FileInputStream(checkstyleConfig)) {
                RewriteCheckstyle rewriteCheckstyle = new RewriteCheckstyle(checkstyleIn, excludes == null ? Collections.emptySet() : excludes, null);

                rewriteSourceSet(meterRegistry, rewriteCheckstyle, project.getBuild().getSourceDirectory());
                rewriteSourceSet(meterRegistry, rewriteCheckstyle, project.getBuild().getTestSourceDirectory());

                if(metricsClient != null) {
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
    }

    private void rewriteSourceSet(MeterRegistry meterRegistry, RewriteCheckstyle rewriteCheckstyle, String sourceDirectory) throws IOException {
        Path sourceRoot = new File(sourceDirectory).toPath();
        List<Path> sources = Files.walk(sourceRoot)
                .filter(f -> !Files.isDirectory(f) && f.endsWith(".java"))
                .collect(Collectors.toList());

        List<J.CompilationUnit> cus = new JavaParser()
                .setLogCompilationWarningsAndErrors(false)
                .setMeterRegistry(meterRegistry)
                .parse(sources);

        List<Change<J.CompilationUnit>> changes = cus.stream()
                .map(cu -> rewriteCheckstyle.apply(cu.refactor().setMeterRegistry(meterRegistry)).fix())
                .filter(change -> !change.getRulesThatMadeChanges().isEmpty())
                .collect(toList());

        if (!changes.isEmpty()) {
            for (Change<J.CompilationUnit> change : changes) {
                getLog().warn("The following checkstyle rules in " + change.getOriginal().getSourcePath() + " have been fixed:");
                for (String rule : change.getRulesThatMadeChanges()) {
                    getLog().warn("   " + rule);
                }
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(reportOutputDirectory.toPath())) {
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

    private MeterRegistry getMeterRegistry() {
        if(metricsUri == null) {
            return new CompositeMeterRegistry();
        }
        else if(metricsUri.equals("LOG")) {
            return new LoggingMeterRegistry();
        }
        else {
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
            } catch(Throwable t) {
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
