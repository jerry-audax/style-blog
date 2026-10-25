package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.domain.asset.inventory.gateway.MediaInventoryGateway;
import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryObject;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaInventoryException;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.infrastructure.configuration.asset.ImgBedSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Read-only ImgBed inventory adapter for MEDIA-02.
 *
 * It deliberately has no insert/update/delete operation: a preview cannot
 * mutate the provider or the PostgreSQL database.
 */
public final class CloudflareMediaInventoryRepository implements MediaInventoryGateway {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_FILES = 2000;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final Set<String> IMAGE_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    private static final Set<String> AUDIO_TYPES =
            Set.of(
                    "audio/mpeg",
                    "audio/mp3",
                    "audio/mp4",
                    "audio/m4a",
                    "audio/x-m4a",
                    "audio/aac",
                    "audio/ogg",
                    "audio/wav",
                    "audio/x-wav",
                    "audio/flac",
                    "audio/webm");

    private final ImgBedSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final boolean strictProjection;

    public CloudflareMediaInventoryRepository(ImgBedSettings settings, ObjectMapper mapper) {
        this(settings, mapper, false);
    }

    /** Migration previews fail closed rather than silently discarding inconsistent objects. */
    public CloudflareMediaInventoryRepository(
            ImgBedSettings settings, ObjectMapper mapper, boolean strictProjection) {
        this.settings = Objects.requireNonNull(settings, "图床配置不能为空");
        this.mapper = Objects.requireNonNull(mapper, "JSON 映射器不能为空");
        this.strictProjection = strictProjection;
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    @Override
    public List<MediaInventoryObject> list(MediaKind kind) {
        Objects.requireNonNull(kind, "资源类型不能为空");
        String fileType = fileType(kind);
        int start = 0;
        int total = -1;
        Set<String> seen = new HashSet<>();
        List<MediaInventoryObject> result = new ArrayList<>();

        do {
            JsonNode page = readPage(fileType, start);
            JsonNode files = page.path("files");
            JsonNode totalCount = page.path("totalCount");
            if (!files.isArray()
                    || files.size() > PAGE_SIZE
                    || !totalCount.canConvertToInt()
                    || totalCount.intValue() < 0
                    || totalCount.intValue() > MAX_FILES) {
                throw new MediaInventoryException("图床盘点响应分页结构无效");
            }
            if (total == -1) {
                total = totalCount.intValue();
            } else if (total != totalCount.intValue()) {
                throw new MediaInventoryException("图床盘点总数在分页间发生变化");
            }
            if (start + files.size() > total || (files.isEmpty() && start < total)) {
                throw new MediaInventoryException("图床盘点分页不完整");
            }
            for (JsonNode file : files) {
                project(file, kind)
                        .ifPresent(
                                item -> {
                                    if (!seen.add(item.objectKey().value())) {
                                        throw new MediaInventoryException("图床盘点发现重复资源路径");
                                    }
                                    result.add(item);
                                });
            }
            start += files.size();
        } while (start < total);

        result.sort(Comparator.comparing(item -> item.objectKey().value()));
        return List.copyOf(result);
    }

    private JsonNode readPage(String fileType, int start) {
        String query =
                "dir="
                        + encode(settings.rootFolder)
                        + "&recursive=true&fileType="
                        + encode(fileType)
                        + "&accessStatus=normal&start="
                        + start
                        + "&count="
                        + PAGE_SIZE;
        HttpRequest request =
                HttpRequest.newBuilder(settings.baseUrl.resolve("/api/manage/list?" + query))
                        .timeout(settings.timeout)
                        .header("Authorization", "Bearer " + settings.token)
                        .header("Accept", "application/json")
                        .header("Cache-Control", "no-store")
                        .GET()
                        .build();
        CompletableFuture<HttpResponse<byte[]>> pending =
                http.sendAsync(request, info -> new LimitedBody());
        try {
            HttpResponse<byte[]> response =
                    pending.get(settings.timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MediaInventoryException("图床盘点请求失败");
            }
            JsonNode body = mapper.readTree(response.body());
            if (body == null) throw new MediaInventoryException("图床盘点响应为空");
            return body;
        } catch (MediaInventoryException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new MediaInventoryException("图床盘点请求中断", error);
        } catch (TimeoutException | ExecutionException | java.io.IOException error) {
            throw new MediaInventoryException("图床盘点请求失败", error);
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private Optional<MediaInventoryObject> project(JsonNode file, MediaKind kind) {
        String rawName = file.path("name").asText("");
        if (rawName.isBlank()) return rejectedProjection();
        try {
            MediaObjectKey key = new MediaObjectKey(rawName);
            key.requireWithin(settings.rootFolder);
            String leaf = rawName.substring(rawName.lastIndexOf('/') + 1);
            if (leaf.length() > 255) return rejectedProjection();

            JsonNode metadata = file.path("metadata");
            String mime =
                    metadata.path("File-Mime")
                            .asText(metadata.path("FileType").asText(""))
                            .trim()
                            .toLowerCase(Locale.ROOT);
            if (!matchesKind(kind, mime)) return rejectedProjection();
            Long size = nullableNonNegativeLong(metadata, "FileSizeBytes", "File-Size");
            MediaMetadata facts =
                    new MediaMetadata(
                            leaf, mime.isBlank() ? null : mime, size, null, null, null, null);
            return Optional.of(new MediaInventoryObject(kind, key, facts));
        } catch (IllegalArgumentException ignored) {
            // Unsafe paths, invalid metadata and objects outside blog/ are not
            // imported or reported as managed resources.
            return rejectedProjection();
        }
    }

    private Optional<MediaInventoryObject> rejectedProjection() {
        if (strictProjection) throw new MediaInventoryException("图床盘点包含不安全或类型不一致的对象，已终止迁移预览");
        return Optional.empty();
    }

    private static boolean matchesKind(MediaKind kind, String mime) {
        if (mime.isBlank()) return true; // Unknown provider metadata stays NULL.
        return switch (kind) {
            case IMAGE -> IMAGE_TYPES.contains(mime);
            case AUDIO -> AUDIO_TYPES.contains(mime);
            case VIDEO -> mime.startsWith("video/");
            case LYRICS -> mime.startsWith("text/");
        };
    }

    private static Long nullableNonNegativeLong(
            JsonNode metadata, String primary, String fallback) {
        JsonNode value = metadata.path(primary);
        if (!value.isNumber()) value = metadata.path(fallback);
        if (!value.isNumber() || !value.canConvertToLong() || value.longValue() < 0) return null;
        return value.longValue();
    }

    private static String fileType(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> "image";
            case AUDIO -> "audio";
            case VIDEO -> "video";
            case LYRICS -> "other";
        };
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private boolean rejected;

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (rejected) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BODY_BYTES - output.size()) {
                    rejected = true;
                    subscription.cancel();
                    result.completeExceptionally(new MediaInventoryException("图床盘点响应过大"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                output.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            if (!rejected) result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            if (!rejected) result.complete(output.toByteArray());
        }
    }
}
