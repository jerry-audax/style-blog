package com.blogsystem.music.infrastructure.imgbed;

import com.blogsystem.asset.infrastructure.configuration.ImgBedSettings;
import com.blogsystem.music.domain.model.*;
import com.blogsystem.music.domain.repository.HostedAudioRepository;
import com.fasterxml.jackson.databind.*;

import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

/**
 * Read-only provider adapter. The direct music folder is the explicitly public namespace.
 */
public final class CloudflareHostedAudioRepository implements HostedAudioRepository {
    private static final int PAGE_SIZE = 100, MAX_FILES = 2000, MAX_BODY_BYTES = 1024 * 1024;
    private final ImgBedSettings settings;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Clock clock;
    private final String folder;
    private List<HostedAudio> cached = List.of();
    private Instant validUntil = Instant.MIN, retryAfter = Instant.MIN;

    public CloudflareHostedAudioRepository(ImgBedSettings settings, ObjectMapper json) {
        this(settings, json, Clock.systemUTC());
    }

    public CloudflareHostedAudioRepository(ImgBedSettings settings, ObjectMapper json, Clock clock) {
        this.settings = settings;
        this.json = json;
        this.clock = clock;
        this.folder = settings.rootFolder + "/music";
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public synchronized List<HostedAudio> findPublished() {
        Instant now = clock.instant();
        if (now.isBefore(validUntil)) return cached;
        if (settings.token.isBlank() || now.isBefore(retryAfter)) throw new HostedMusicUnavailable();
        try {
            List<HostedAudio> complete = readAll();
            cached = List.copyOf(complete);
            validUntil = clock.instant().plusSeconds(60);
            retryAfter = Instant.MIN;
            return cached;
        } catch (RuntimeException error) {
            retryAfter = clock.instant().plusSeconds(5);
            throw new HostedMusicUnavailable();
        }
    }

    @Override
    public synchronized void invalidate() {
        cached = List.of();
        validUntil = Instant.MIN;
        retryAfter = Instant.MIN;
    }

    private List<HostedAudio> readAll() {
        long deadline = System.nanoTime() + settings.timeout.toNanos();
        int start = 0, total = -1;
        Set<String> seen = new HashSet<>();
        List<HostedAudio> songs = new ArrayList<>();
        do {
            String route = "/api/manage/list?dir=" + encode(folder)
                    + "&recursive=false&fileType=audio&accessStatus=normal&start=" + start + "&count=" + PAGE_SIZE;
            JsonNode result = readPage(route, deadline);
            JsonNode files = result.path("files"), count = result.path("totalCount");
            if (!files.isArray() || files.size() > PAGE_SIZE || !count.isIntegralNumber() || !count.canConvertToInt()
                    || count.intValue() < 0 || count.intValue() > MAX_FILES) throw new HostedMusicUnavailable();
            if (total != -1 && total != count.intValue()) throw new HostedMusicUnavailable();
            total = count.intValue();
            if (start + files.size() > total || (files.isEmpty() && start < total)) throw new HostedMusicUnavailable();
            for (JsonNode file : files) {
                String path = file.path("name").asText("");
                if (!seen.add(path)) throw new HostedMusicUnavailable();
                project(file, path).ifPresent(songs::add);
            }
            start += files.size();
        } while (start < total);
        songs.sort(Comparator.comparing(HostedAudio::name, String.CASE_INSENSITIVE_ORDER).thenComparing(HostedAudio::id));
        return songs;
    }

    private Optional<HostedAudio> project(JsonNode file, String path) {
        if (!path.startsWith(folder + "/")) return Optional.empty();
        String leaf = path.substring(folder.length() + 1);
        // Direct objects only; do not expose subdirectories/probes or encoded traversal/query syntax.
        if (leaf.isBlank() || leaf.equals(".") || leaf.equals("..") || leaf.length() > 512
                || leaf.matches(".*[\\\\/%?#\\p{Cntrl}].*")) return Optional.empty();
        JsonNode meta = file.path("metadata");
        String mime = meta.path("File-Mime").asText(meta.path("FileType").asText("")).toLowerCase(Locale.ROOT);
        String listType = meta.path("ListType").asText("");
        if (!HostedAudio.MEDIA_TYPES.contains(mime) || "Block".equalsIgnoreCase(listType)
                || ("adult".equalsIgnoreCase(meta.path("Label").asText()) && !"White".equalsIgnoreCase(listType)))
            return Optional.empty();
        String name = meta.path("FileName").asText(leaf).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.contains("/") || name.contains("\\") || name.length() > 512) name = leaf;
        name = name.replaceFirst("(?i)\\.(m4a|mp4|mp3|aac|ogg|oga|wav|flac|webm)$", "").trim();
        if (name.isBlank()) name = "未命名歌曲";
        long size = Math.max(0, meta.path("FileSizeBytes").asLong(meta.path("File-Size").asLong(0)));
        String encodedPath = Arrays.stream(path.split("/")).map(CloudflareHostedAudioRepository::encode)
                .collect(Collectors.joining("/"));
        return Optional.of(new HostedAudio(path, name, settings.baseUrl.resolve("/file/" + encodedPath).toASCIIString(), mime, size));
    }

    private JsonNode readPage(String route, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new HostedMusicUnavailable();
        HttpRequest request = HttpRequest.newBuilder(settings.baseUrl.resolve(route)).timeout(settings.timeout)
                .header("Authorization", "Bearer " + settings.token).header("Accept", "application/json")
                .header("Cache-Control", "no-store").GET().build();
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, info -> new LimitedBody());
        try {
            HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) throw new HostedMusicUnavailable();
            JsonNode result = json.readTree(response.body());
            if (result == null) throw new HostedMusicUnavailable();
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new HostedMusicUnavailable();
        } catch (Exception error) {
            throw new HostedMusicUnavailable();
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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
            if (size > MAX_BODY_BYTES) {
                rejected = true;
                subscription.cancel();
                delegate.onError(new HostedMusicUnavailable());
            } else delegate.onNext(buffers);
        }

        public void onError(Throwable error) {
            if (!rejected) delegate.onError(new HostedMusicUnavailable());
        }

        public void onComplete() {
            if (!rejected) delegate.onComplete();
        }
    }
}
