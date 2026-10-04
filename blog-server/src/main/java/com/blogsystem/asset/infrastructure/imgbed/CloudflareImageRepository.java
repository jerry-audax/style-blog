package com.blogsystem.asset.infrastructure.imgbed;

import com.blogsystem.asset.domain.model.*;
import com.blogsystem.asset.domain.repository.ImageRepository;
import com.blogsystem.asset.infrastructure.configuration.ImgBedSettings;
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

/**
 * Provider protocol only; never stores local files or writes file_record.
 */
public final class CloudflareImageRepository implements ImageRepository {
    private final ImgBedSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public CloudflareImageRepository(ImgBedSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public StoredImage upload(ImageUpload image, long authenticatedUserId) {
        String folder = settings.rootFolder + switch (image.purpose()) {
            case ARTICLE -> "/articles";
            case COVER -> "/covers";
            case AVATAR -> "/avatars/" + authenticatedUserId;
        };
        String boundary = "BlogImage" + UUID.randomUUID().toString().replace("-", "");
        String name = UUID.randomUUID() + "." + image.extension();
        byte[] header = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name +
                "\"\r\nContent-Type: " + image.mediaType() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] ending = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        String query = "uploadChannel=" + encode(settings.uploadChannel) + "&channelName=" + encode(settings.channelName) +
                "&uploadFolder=" + encode(folder) + "&returnFormat=full&uploadNameType=index&autoRetry=false&serverCompress=false";
        var body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(header),
                HttpRequest.BodyPublishers.ofByteArray(image.bytes()), HttpRequest.BodyPublishers.ofByteArray(ending));
        JsonNode result = execute(request("/upload?" + query).header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(body).build(), true);
        try {
            if (!result.isArray() || result.size() != 1) throw new IllegalArgumentException();
            URI source = URI.create(result.get(0).path("src").asText());
            if (source.isAbsolute() && (!Objects.equals(source.getScheme(), settings.baseUrl.getScheme()) ||
                    !Objects.equals(source.getAuthority(), settings.baseUrl.getAuthority())))
                throw new IllegalArgumentException();
            if (source.getRawQuery() != null || source.getRawFragment() != null || source.getRawUserInfo() != null ||
                    !source.getRawPath().startsWith("/file/")) throw new IllegalArgumentException();
            String decoded = URLDecoder.decode(source.getRawPath().substring(6).replace("+", "%2B"), StandardCharsets.UTF_8);
            ImagePath path = new ImagePath(decoded);
            path.requireWithin(folder);
            return new StoredImage(path.value(), publicUrl(path), image.mediaType(), image.size());
        } catch (RuntimeException ignored) {
            throw failure(ImageStorageException.Reason.BAD_RESPONSE);
        }
    }

    @Override
    public ImagePage list(int offset, int count, String search) {
        String query = "dir=" + encode(settings.rootFolder) + "&recursive=true&fileType=image&start=" + offset + "&count=" + count + "&search=" + encode(search);
        JsonNode result = execute(request("/api/manage/list?" + query).GET().build(), false);
        if (!result.path("files").isArray() || !result.path("totalCount").canConvertToLong() || result.path("files").size() > count)
            throw failure(ImageStorageException.Reason.BAD_RESPONSE);
        List<StoredImage> images = new ArrayList<>();
        for (JsonNode file : result.path("files")) {
            try {
                ImagePath path = new ImagePath(file.path("name").asText());
                path.requireWithin(settings.rootFolder);
                JsonNode metadata = file.path("metadata");
                String mediaType = metadata.path("File-Mime").asText(metadata.path("FileType").asText(""));
                if (!Set.of("image/png", "image/jpeg", "image/gif", "image/webp").contains(mediaType)) continue;
                long size = metadata.path("FileSizeBytes").asLong(metadata.path("File-Size").asLong(0));
                images.add(new StoredImage(path.value(), publicUrl(path), mediaType, Math.max(0, size)));
            } catch (
                    IllegalArgumentException ignored) { /* Do not expose objects outside our namespace or unsafe formats. */ }
        }
        return new ImagePage(images, Math.max(0, result.path("totalCount").asLong()));
    }

    @Override
    public void delete(ImagePath path) {
        path.requireWithin(settings.rootFolder);
        JsonNode result = execute(request("/api/manage/delete/" + encodePath(path.value()) + "?folder=false").GET().build(), true);
        if (!result.path("success").isBoolean() || !result.path("success").booleanValue())
            throw failure(ImageStorageException.Reason.UNAVAILABLE);
    }

    private HttpRequest.Builder request(String route) {
        if (settings.token.isBlank()) throw failure(ImageStorageException.Reason.NOT_CONFIGURED);
        return HttpRequest.newBuilder(settings.baseUrl.resolve(route)).timeout(settings.timeout)
                .header("Authorization", "Bearer " + settings.token).header("Accept", "application/json")
                .header("Cache-Control", "no-cache, no-store");
    }

    private JsonNode execute(HttpRequest request, boolean mutation) {
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, info -> new BoundedBodySubscriber());
        try {
            HttpResponse<byte[]> response = pending.get(settings.timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw failure(ImageStorageException.Reason.UNAVAILABLE);
            JsonNode json = mapper.readTree(response.body());
            if (json == null) throw failure(ImageStorageException.Reason.BAD_RESPONSE);
            return json;
        } catch (ImageStorageException error) {
            throw error;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw failure(mutation ? ImageStorageException.Reason.UNKNOWN_OUTCOME : ImageStorageException.Reason.UNAVAILABLE);
        } catch (TimeoutException | ExecutionException ignored) {
            throw failure(mutation ? ImageStorageException.Reason.UNKNOWN_OUTCOME : ImageStorageException.Reason.UNAVAILABLE);
        } catch (Exception ignored) {
            throw failure(ImageStorageException.Reason.BAD_RESPONSE);
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private String publicUrl(ImagePath path) {
        return settings.baseUrl.resolve("/file/" + encodePath(path.value())).toASCIIString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodePath(String path) {
        return Arrays.stream(path.split("/")).map(CloudflareImageRepository::encode).collect(Collectors.joining("/"));
    }

    private static ImageStorageException failure(ImageStorageException.Reason reason) {
        return new ImageStorageException(reason);
    }

    /**
     * Limit response memory and complete the request future only when the body is consumed.
     */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() {
            return result;
        }

        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 1024 * 1024 - output.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("Response too large"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                output.writeBytes(chunk);
            }
            subscription.request(1);
        }

        public void onError(Throwable ignored) {
            result.completeExceptionally(new IllegalStateException("Response failed"));
        }

        public void onComplete() {
            result.complete(output.toByteArray());
        }
    }
}
