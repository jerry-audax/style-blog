package com.blogsystem.infrastructure.gateway.publication;

import com.blogsystem.infrastructure.gateway.publication.HttpSitePublicationRepository;

import org.assertj.core.api.Assertions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class HttpSitePublicationRepositoryTest {
    @Test
    void mapsPrivateStatusAndRetryWithoutLeakingWorkerCredential() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var authenticated = new AtomicBoolean();
        server.createContext("/internal/", exchange -> {
            authenticated.set("fixture-publication-key-32-characters".equals(exchange.getRequestHeaders().getFirst("X-Publication-Key")));
            var body = "{\"configured\":true,\"phase\":\"CURRENT\",\"publishedDigest\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"desiredDigest\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"publishedAt\":\"2026-10-04T00:00:00Z\",\"checkedAt\":null,\"error\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(exchange.getRequestMethod().equals("POST") ? 202 : 200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var repository = new HttpSitePublicationRepository(new ObjectMapper(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()), () -> "fixture-publication-key-32-characters");
            assertThat(repository.status().phase()).isEqualTo("CURRENT");
            assertThat(authenticated).isTrue();
            assertThat(repository.retry().configured()).isTrue();
            assertThat(new ObjectMapper().writeValueAsString(repository.status())).doesNotContain("fixture-publication");
        } finally {server.stop(0);}
    }

    @Test
    void missingCredentialsAndTransportFailuresAreVisibleWithoutRawSecrets() {
        var repository = new HttpSitePublicationRepository(new ObjectMapper(), URI.create("http://127.0.0.1:1"), () -> "");
        assertThat(repository.status().configured()).isFalse();
        assertThat(repository.status().phase()).isEqualTo("UNAVAILABLE");
        assertThatThrownBy(repository::retry).hasMessageContaining("发布服务");
        var unreachable = new HttpSitePublicationRepository(new ObjectMapper(), URI.create("http://127.0.0.1:1"), () -> "fixture-publication-key-32-characters");
        assertThat(unreachable.status().phase()).isEqualTo("UNAVAILABLE");
        assertThat(unreachable.status().error()).doesNotContain("127.0.0.1", "fixture");
    }
}
