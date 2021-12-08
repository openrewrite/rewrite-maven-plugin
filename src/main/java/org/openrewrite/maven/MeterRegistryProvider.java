package org.openrewrite.maven;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.micrometer.prometheus.rsocket.PrometheusRSocketClient;
import io.prometheus.client.CollectorRegistry;
import io.rsocket.transport.ClientTransport;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.transport.netty.client.WebsocketClientTransport;
import org.apache.maven.plugin.logging.Log;
import org.openrewrite.internal.lang.Nullable;
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.TcpClient;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;

public class MeterRegistryProvider implements AutoCloseable {
    private final Log log;
    @Nullable
    private final String uriString;
    @Nullable
    private final String username;
    @Nullable
    private final String password;

    @Nullable
    private PrometheusRSocketClient metricsClient;

    @Nullable
    private MeterRegistry registry;

    public MeterRegistryProvider(Log log, @Nullable String uriString, @Nullable String username, @Nullable String password) {
        this.log = log;
        this.uriString = uriString;
        this.username = username;
        this.password = password;
    }

    public MeterRegistry registry() {
        this.registry = buildRegistry();
        return this.registry;
    }

    private MeterRegistry buildRegistry() {
        if (uriString == null) {
            return new CompositeMeterRegistry();
        } else if (uriString.equals("LOG")) {
            return new MavenLoggingMeterRegistry(log);
        } else {
            try {
                URI uri = URI.create(uriString);
                PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, new CollectorRegistry(), Clock.SYSTEM);

                ClientTransport clientTransport;
                switch (uri.getScheme()) {
                    case "ephemeral":
                    case "https":
                    case "wss": {
                        TcpClient tcpClient = TcpClient.create().secure().host(uri.getHost()).port(uri.getPort() == -1 ? 443 : uri.getPort());
                        clientTransport = getWebsocketClientTransport(tcpClient);
                        break;
                    }
                    case "http":
                    case "ws": {
                        TcpClient tcpClient = TcpClient.create().host(uri.getHost()).port(uri.getPort() == -1 ? 80 : uri.getPort());
                        clientTransport = getWebsocketClientTransport(tcpClient);
                        break;
                    }
                    case "tcp":
                        clientTransport = TcpClientTransport.create(uri.getHost(), uri.getPort());
                        break;
                    default:
                        log.warn("Unable to publish metrics. Unrecognized scheme " + uri.getScheme());
                        return new CompositeMeterRegistry();
                }

                metricsClient = PrometheusRSocketClient
                        .build(meterRegistry, meterRegistry::scrape, clientTransport)
                        .retry(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(3)))
                        .connect();

                return meterRegistry;
            } catch (Throwable t) {
                log.warn("Unable to publish metrics", t);
            }
        }

        return new CompositeMeterRegistry();
    }

    private ClientTransport getWebsocketClientTransport(TcpClient tcpClient) {
        HttpClient httpClient = HttpClient.from(tcpClient).wiretap(true);
        if (username != null && password != null) {
            httpClient = httpClient.headers(h -> h.add("Authorization", "Basic: " + Base64.getUrlEncoder()
                    .encodeToString((username + ":" + password).getBytes())));
        }
        return WebsocketClientTransport.create(httpClient, "/");
    }

    @Override
    public void close() {
        if (metricsClient != null) {
            try {
                // Don't bother blocking long here. If the build ends before the dying push can happen, so be it.
                metricsClient.pushAndClose().block(Duration.ofSeconds(3));
            } catch (Throwable ignore) {
                // sometimes fails when connection already closed, e.g. due to flaky internet connection
            }
        }

        if (registry != null) {
            registry.close();
        }
    }
}
