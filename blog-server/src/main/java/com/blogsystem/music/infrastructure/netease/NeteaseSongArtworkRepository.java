package com.blogsystem.music.infrastructure.netease;

import com.blogsystem.music.domain.repository.SongArtworkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

/**
 * Public website metadata only, NOT an authenticated developer-platform playback API.
 */
@Repository
public class NeteaseSongArtworkRepository implements SongArtworkRepository {
    private static final URI ENDPOINT = URI.create("https://music.163.com/api/song/detail/");
    private final HttpClient http;
    private final ObjectMapper json;
    private final URI endpoint;
    private final Clock clock;
    private final Map<String, Cached> cache = new HashMap<>();
    private Instant retryAfter = Instant.MIN;

    private record Cached(String cover, Instant expires) {
    }

    @Autowired
    public NeteaseSongArtworkRepository(ObjectMapper json) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build(), json, ENDPOINT, Clock.systemUTC());
    }

    NeteaseSongArtworkRepository(HttpClient http, ObjectMapper json, URI endpoint, Clock clock) {
        this.http = http;
        this.json = json;
        this.endpoint = endpoint;
        this.clock = clock;
    }

    @Override
    public synchronized Map<String, String> findCovers(Set<String> songIds) {
        var now = clock.instant();
        cache.entrySet().removeIf(e -> !e.getValue().expires().isAfter(now));
        var ids = songIds.stream().filter(Objects::nonNull).filter(id -> id.matches("[1-9]\\d{0,19}"))
                .sorted().limit(2000).toList();
        var missing = ids.stream().filter(id -> !cache.containsKey(id)).toList();
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        if (!now.isBefore(retryAfter)) for (int start = 0; start < missing.size(); start += 50) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            var batch = missing.subList(start, Math.min(start + 50, missing.size()));
            try {
                String encoded = URLEncoder.encode(json.writeValueAsString(batch), StandardCharsets.UTF_8);
                var request = HttpRequest.newBuilder(URI.create(endpoint + "?ids=" + encoded))
                        .timeout(Duration.ofNanos(remaining)).header("Referer", "https://music.163.com/")
                        .header("Accept", "application/json").GET().build();
                var pending = http.sendAsync(request, info -> new LimitedBody());
                HttpResponse<byte[]> response;
                try {
                    response = pending.get(remaining, TimeUnit.NANOSECONDS);
                } finally {
                    pending.cancel(true);
                }
                if (response.statusCode() != 200 || response.body().length > 1_048_576)
                    throw new IllegalStateException("Artwork unavailable");
                var body = json.readTree(response.body());
                if (body.path("code").asInt() != 200 || !body.path("songs").isArray())
                    throw new IllegalStateException("Invalid artwork response");
                Map<String, String> found = new HashMap<>();
                for (var song : body.path("songs")) {
                    String id = song.path("id").asText();
                    String cover = safeCover(song.path("album").path("picUrl").asText(""));
                    if (batch.contains(id) && !cover.isEmpty()) found.put(id, cover);
                }
                for (String id : batch) {
                    String cover = found.getOrDefault(id, "");
                    cache.put(id, new Cached(cover, now.plus(cover.isEmpty() ? Duration.ofMinutes(10) : Duration.ofHours(24))));
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                retryAfter = now.plusSeconds(60);
                break;
            } catch (Exception error) {
                // Optional artwork must not affect audio; no raw response/diagnostics are logged.
                retryAfter = now.plusSeconds(60);
                break;
            }
        }
        Map<String, String> result = new HashMap<>();
        for (var id : ids) {
            var value = cache.get(id);
            if (value != null && !value.cover().isEmpty()) result.put(id, value.cover());
        }
        return Map.copyOf(result);
    }

    static String safeCover(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null && host.endsWith(".music.126.net")
                    && uri.getUserInfo() == null && uri.getPort() == -1 && uri.getQuery() == null
                    && uri.getFragment() == null ? uri.toASCIIString() : "";
        } catch (IllegalArgumentException error) {
            return "";
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;
        private boolean rejected;

        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        public void onNext(List<ByteBuffer> buffers) {
            if (rejected) return;
            for (ByteBuffer buffer : buffers) size += buffer.remaining();
            if (size > 1_048_576) {
                rejected = true;
                subscription.cancel();
                delegate.onError(new IllegalStateException("Artwork response too large"));
            } else delegate.onNext(buffers);
        }

        public void onError(Throwable error) {
            if (!rejected) delegate.onError(error);
        }

        public void onComplete() {
            if (!rejected) delegate.onComplete();
        }
    }
}
