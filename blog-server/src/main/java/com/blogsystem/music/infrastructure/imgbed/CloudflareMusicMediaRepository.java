package com.blogsystem.music.infrastructure.imgbed;

import com.blogsystem.asset.infrastructure.configuration.ImgBedSettings;
import com.blogsystem.music.domain.model.*;
import com.blogsystem.music.domain.repository.MusicMediaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

/** Cloudflare ImgBed adapter for the blog-owned music namespace. */
public final class CloudflareMusicMediaRepository implements MusicMediaRepository {
    private final ImgBedSettings settings;
    private final ObjectMapper json;
    private final HttpClient http;

    public CloudflareMusicMediaRepository(ImgBedSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public MusicMedia upload(MusicMediaUpload upload) {
        String folder = settings.rootFolder + "/music/" + switch (upload.kind()) {
            case AUDIO -> "";
            case COVER -> "covers";
            case LYRICS -> "lyrics";
        };
        String boundary = "BlogMusic" + UUID.randomUUID().toString().replace("-", "");
        String extension = extension(upload.filename(), upload.mediaType(), upload.kind());
        String filename = UUID.randomUUID() + extension;
        byte[] header = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: " + upload.mediaType() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] ending = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        String query = "uploadChannel=" + encode(settings.uploadChannel) + "&channelName=" + encode(settings.channelName)
                + "&uploadFolder=" + encode(folder) + "&returnFormat=full&uploadNameType=index&autoRetry=false&serverCompress=false";
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(header),
                HttpRequest.BodyPublishers.ofByteArray(upload.bytes()), HttpRequest.BodyPublishers.ofByteArray(ending));
        JsonNode result = execute(request("/upload?" + query).header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(body).build());
        try {
            if (!result.isArray() || result.size() != 1) throw new IllegalArgumentException();
            URI source = URI.create(result.get(0).path("src").asText());
            if (!Objects.equals(source.getScheme(), settings.baseUrl.getScheme()) || !Objects.equals(source.getAuthority(), settings.baseUrl.getAuthority())
                    || source.getRawQuery() != null || source.getRawFragment() != null || !source.getRawPath().startsWith("/file/"))
                throw new IllegalArgumentException();
            String path = URLDecoder.decode(source.getRawPath().substring(6).replace("+", "%2B"), StandardCharsets.UTF_8);
            requireMusicPath(path);
            return new MusicMedia(path, publicUrl(path), upload.mediaType(), upload.bytes().length);
        } catch (RuntimeException error) {
            throw new MusicException(MusicException.Reason.INVALID_RESPONSE);
        }
    }

    @Override
    public void delete(String path) {
        requireMusicPath(path);
        JsonNode result = execute(request("/api/manage/delete/" + encodePath(path) + "?folder=false").GET().build());
        if (!result.path("success").asBoolean(false)) throw new MusicException(MusicException.Reason.UNAVAILABLE);
    }

    @Override
    public String publicUrl(String path) {
        requireMusicPath(path);
        return settings.baseUrl.resolve("/file/" + encodePath(path)).toASCIIString();
    }

    private HttpRequest.Builder request(String route) {
        if (settings.token.isBlank()) throw new MusicException(MusicException.Reason.NOT_CONFIGURED);
        return HttpRequest.newBuilder(settings.baseUrl.resolve(route)).timeout(settings.timeout)
                .header("Authorization", "Bearer " + settings.token).header("Accept", "application/json")
                .header("Cache-Control", "no-cache, no-store");
    }

    private JsonNode execute(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, info -> new BodySubscriber());
        try {
            HttpResponse<byte[]> response = pending.get(settings.timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new MusicException(MusicException.Reason.UNAVAILABLE);
            JsonNode body = json.readTree(response.body());
            if (body == null) throw new MusicException(MusicException.Reason.INVALID_RESPONSE);
            return body;
        } catch (MusicException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new MusicException(MusicException.Reason.UNAVAILABLE);
        } catch (TimeoutException | ExecutionException | java.io.IOException error) {
            throw new MusicException(MusicException.Reason.UNAVAILABLE);
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private void requireMusicPath(String path) {
        if (path == null || !path.startsWith(settings.rootFolder + "/music/") || path.contains("..") || path.contains("\\")
                || path.contains("?") || path.contains("#") || path.length() > 512)
            throw new IllegalArgumentException("Invalid music media path");
    }

    private static String extension(String filename, String mime, MusicMediaKind kind) {
        String lower = filename.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot > -1 && dot < lower.length() - 1) return lower.substring(dot).replaceAll("[^a-z0-9.]", "");
        return switch (kind) {
            case AUDIO -> mime.equals("audio/mpeg") || mime.equals("audio/mp3") ? ".mp3" : ".audio";
            case COVER -> "." + mime.substring(mime.indexOf('/') + 1).replace("jpeg", "jpg");
            case LYRICS -> ".lrc";
        };
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String encodePath(String path) { return Arrays.stream(path.split("/")).map(CloudflareMusicMediaRepository::encode).collect(Collectors.joining("/")); }

    private static final class BodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 1024 * 1024 - output.size()) { subscription.cancel(); result.completeExceptionally(new IllegalStateException()); return; }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); output.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(output.toByteArray()); }
    }
}
