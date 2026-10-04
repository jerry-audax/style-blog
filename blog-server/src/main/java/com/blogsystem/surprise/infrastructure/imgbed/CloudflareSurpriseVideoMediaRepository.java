package com.blogsystem.surprise.infrastructure.imgbed;

import com.blogsystem.asset.infrastructure.configuration.ImgBedSettings;
import com.blogsystem.surprise.domain.model.*;
import com.blogsystem.surprise.domain.repository.SurpriseVideoMediaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

/** Cloudflare ImgBed adapter for the managed surprise-video namespace. */
public final class CloudflareSurpriseVideoMediaRepository implements SurpriseVideoMediaRepository {
    private final ImgBedSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public CloudflareSurpriseVideoMediaRepository(ImgBedSettings settings, ObjectMapper mapper) { this.settings = settings; this.mapper = mapper; }

    @Override public SurpriseVideoMedia upload(SurpriseVideoUpload upload) {
        String folder = settings.rootFolder + "/surprise";
        String boundary = "BlogSurprise" + UUID.randomUUID().toString().replace("-", "");
        String extension = extension(upload.filename(), upload.mediaType());
        String filename = UUID.randomUUID() + extension;
        byte[] header = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: " + upload.mediaType() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] ending = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        String query = "uploadChannel=" + encode(settings.uploadChannel) + "&channelName=" + encode(settings.channelName) + "&uploadFolder=" + encode(folder) + "&returnFormat=full&uploadNameType=index&autoRetry=false&serverCompress=false";
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(header), HttpRequest.BodyPublishers.ofByteArray(upload.bytes()), HttpRequest.BodyPublishers.ofByteArray(ending));
        JsonNode result = execute(request("/upload?" + query).header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(body).build());
        try {
            if (!result.isArray() || result.size() != 1) throw new IllegalArgumentException();
            URI source = URI.create(result.get(0).path("src").asText());
            if (!Objects.equals(source.getScheme(), settings.baseUrl.getScheme()) || !Objects.equals(source.getAuthority(), settings.baseUrl.getAuthority()) || source.getRawQuery() != null || source.getRawFragment() != null || !source.getRawPath().startsWith("/file/")) throw new IllegalArgumentException();
            String path = URLDecoder.decode(source.getRawPath().substring(6).replace("+", "%2B"), StandardCharsets.UTF_8);
            requirePath(path);
            return new SurpriseVideoMedia(path, publicUrl(path), upload.mediaType(), upload.bytes().length);
        } catch (RuntimeException error) { throw new IllegalStateException("图床返回的视频地址无效", error); }
    }

    @Override public void delete(String path) {
        requirePath(path);
        JsonNode result = execute(request("/api/manage/delete/" + encodePath(path) + "?folder=false").GET().build());
        if (!result.path("success").asBoolean(false)) throw new IllegalStateException("图床删除视频失败");
    }

    private HttpRequest.Builder request(String route) {
        if (settings.token.isBlank()) throw new IllegalStateException("图床未配置");
        return HttpRequest.newBuilder(settings.baseUrl.resolve(route)).timeout(settings.timeout).header("Authorization", "Bearer " + settings.token).header("Accept", "application/json");
    }
    private JsonNode execute(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, info -> new BodySubscriber());
        try { HttpResponse<byte[]> response = pending.get(settings.timeout.toMillis(), TimeUnit.MILLISECONDS); if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("图床请求失败"); JsonNode body = mapper.readTree(response.body()); if (body == null) throw new IllegalStateException("图床响应无效"); return body; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("图床请求中断", e); }
        catch (TimeoutException | ExecutionException | java.io.IOException e) { throw new IllegalStateException("图床请求失败", e); }
        finally { if (!pending.isDone()) pending.cancel(true); }
    }
    private void requirePath(String path) { if (path == null || !path.startsWith(settings.rootFolder + "/surprise/") || path.contains("..") || path.contains("\\") || path.contains("?") || path.contains("#") || path.length() > 512) throw new IllegalArgumentException("非法视频路径"); }
    private String publicUrl(String path) { return settings.baseUrl.resolve("/file/" + encodePath(path)).toASCIIString(); }
    private static String extension(String filename, String mime) { String lower = filename.toLowerCase(Locale.ROOT); int dot = lower.lastIndexOf('.'); if (dot > -1 && dot < lower.length() - 1) return lower.substring(dot).replaceAll("[^a-z0-9.]", ""); return "." + mime.substring(mime.indexOf('/') + 1).replace("quicktime", "mov"); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String encodePath(String path) { return Arrays.stream(path.split("/")).map(CloudflareSurpriseVideoMediaRepository::encode).collect(Collectors.joining("/")); }
    private static final class BodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>(); private final ByteArrayOutputStream output = new ByteArrayOutputStream(); private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) { for (ByteBuffer buffer : buffers) { if (buffer.remaining() > 1024 * 1024 - output.size()) { subscription.cancel(); result.completeExceptionally(new IllegalStateException()); return; } byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); output.writeBytes(chunk); } subscription.request(1); }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(output.toByteArray()); }
    }
}
