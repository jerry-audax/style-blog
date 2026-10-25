package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.gateway.MediaUploadStorageGateway;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCandidate;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadContent;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadErrorCode;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadOperationType;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadProbe;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadReceipt;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStorageException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTransport;
import com.blogsystem.domain.asset.upload.policy.MediaUploadPolicy;
import com.blogsystem.infrastructure.configuration.asset.MediaUploadProviderSettings;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** One HTTP call per checkpoint; never retries, redirects, cleans up or deletes provider objects. */
public final class CloudflareMediaUploadRepository implements MediaUploadStorageGateway {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CloudflareMediaUploadRepository.class);
    private final MediaUploadProviderSettings settings;
    private final ObjectMapper json;
    private final HttpClient http;
    private final MediaUploadProviderResponseParser parser;

    public CloudflareMediaUploadRepository(
            MediaUploadProviderSettings settings, ObjectMapper json, HttpClient http) {
        if (http.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("Upload redirects must be disabled");
        this.settings = settings;
        this.json =
                json.copy()
                        .enable(
                                com.fasterxml.jackson.core.JsonParser.Feature
                                        .STRICT_DUPLICATE_DETECTION)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.http = http;
        this.parser = new MediaUploadProviderResponseParser(settings, this.json);
    }

    public CloudflareMediaUploadRepository(
            MediaUploadProviderSettings settings, ObjectMapper json) {
        this(
                settings,
                json,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    @Override
    public MediaUploadReceipt uploadDirect(
            MediaUploadOperation.Intent intent, MediaUploadContent source) {
        requireTransport(intent, MediaUploadTransport.DIRECT);
        var body = new MediaUploadMultipartBody();
        body.file(physicalName(intent), intent.file().mediaType(), source);
        return parser.receipt(
                post(query(intent, true) + "&uploadNameType=origin", body, settings.directTimeout),
                intent);
    }

    @Override
    public String beginChunks(MediaUploadOperation.Intent intent) {
        requireTransport(intent, MediaUploadTransport.CHUNKED);
        var body = common(intent, null);
        return parser.initialized(
                post(query(intent, false) + "&initChunked=true", body, settings.initTimeout));
    }

    @Override
    public void uploadChunk(
            MediaUploadOperation.Intent intent, String uploadId, int index, byte[] bytes) {
        requireTransport(intent, MediaUploadTransport.CHUNKED);
        MediaUploadProviderResponseParser.session(uploadId);
        long expected =
                Math.min(
                        MediaUploadPolicy.CHUNK_BYTES,
                        intent.file().size() - (long) index * MediaUploadPolicy.CHUNK_BYTES);
        if (index < 0 || index >= intent.totalChunks() || bytes == null || bytes.length != expected)
            throw rejected();
        var body = common(intent, uploadId);
        body.field("chunkIndex", Integer.toString(index));
        body.file(physicalName(intent), intent.file().mediaType(), bytes);
        parser.chunk(
                post(query(intent, false) + "&chunked=true", body, settings.chunkTimeout),
                uploadId,
                index);
    }

    @Override
    public MediaUploadReceipt mergeChunks(MediaUploadOperation.Intent intent, String uploadId) {
        requireTransport(intent, MediaUploadTransport.CHUNKED);
        return parser.receipt(
                post(
                        query(intent, true) + "&chunked=true&merge=true",
                        common(intent, MediaUploadProviderResponseParser.session(uploadId)),
                        settings.mergeTimeout),
                intent);
    }

    @Override
    public MediaUploadProbe probe(MediaUploadOperation.Intent intent) {
        List<MediaUploadCandidate> all = new ArrayList<>();
        Set<MediaObjectKey> seen = new HashSet<>();
        Long total = null;
        String indexVersion = null;
        int start = 0;
        try {
            for (int pageIndex = 0; pageIndex < 16; pageIndex++) {
                var request =
                        request(
                                        "/api/manage/list?dir="
                                                + encode(intent.operationFolder().value())
                                                + "&recursive=true&start="
                                                + start
                                                + "&count=2",
                                        settings.directTimeout)
                                .GET()
                                .build();
                var page = parser.page(execute(request), intent);
                if (total != null
                        && (total != page.total()
                                || !Objects.equals(indexVersion, page.indexVersion())))
                    return new MediaUploadProbe(false, all);
                total = page.total();
                indexVersion = page.indexVersion();
                if (start + page.candidates().size() > total)
                    return new MediaUploadProbe(false, all);
                for (var c : page.candidates()) {
                    if (!seen.add(c.objectKey())) return new MediaUploadProbe(false, all);
                    all.add(c);
                    if (all.size() == 2) return new MediaUploadProbe(true, all);
                }
                start += page.candidates().size();
                if (start == total) return new MediaUploadProbe(true, all);
                if (page.candidates().isEmpty()) return new MediaUploadProbe(false, all);
            }
        } catch (MediaUploadStorageException ignored) {
            return new MediaUploadProbe(false, all);
        }
        return new MediaUploadProbe(false, all);
    }

    private MediaUploadMultipartBody common(MediaUploadOperation.Intent intent, String session) {
        var body = new MediaUploadMultipartBody();
        if (session != null) body.field("uploadId", session);
        body.field("originalFileName", physicalName(intent));
        body.field("originalFileType", intent.file().mediaType());
        body.field("totalChunks", Integer.toString(intent.totalChunks()));
        return body;
    }

    private String query(MediaUploadOperation.Intent intent, boolean folder) {
        return "uploadChannel="
                + encode(settings.uploadChannel)
                + "&channelName="
                + encode(settings.channelName)
                + "&autoRetry=false&serverCompress=false"
                + (folder
                        ? "&uploadFolder="
                                + encode(intent.operationFolder().value())
                                + "&returnFormat=full"
                        : "");
    }

    private JsonNode post(String query, MediaUploadMultipartBody body, Duration timeout) {
        return execute(
                request("/upload?" + query, timeout)
                        .header("Content-Type", "multipart/form-data; boundary=" + body.boundary)
                        .POST(body.publisher())
                        .build());
    }

    private HttpRequest.Builder request(String route, Duration timeout) {
        if (settings.token.isBlank()) throw rejected();
        return HttpRequest.newBuilder(settings.baseUrl.resolve(route))
                .timeout(timeout)
                .header("Authorization", "Bearer " + settings.token)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store");
    }

    private JsonNode execute(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = http.sendAsync(request, info -> new BoundedSubscriber());
            var response =
                    pending.get(request.timeout().orElseThrow().toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("ImgBed request outcome requires verification; HTTP status={}", response.statusCode());
                throw MediaUploadProviderResponseParser.unknown();
            }
            JsonNode body = json.readTree(response.body());
            if (body == null) throw MediaUploadProviderResponseParser.unknown();
            return body;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw MediaUploadProviderResponseParser.unknown();
        } catch (Exception ignored) {
            log.warn("ImgBed request outcome requires verification; failure type={}", ignored.getClass().getSimpleName());
            throw MediaUploadProviderResponseParser.unknown();
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private static String physicalName(MediaUploadOperation.Intent intent) {
        String ext =
                switch (intent.file().mediaType()) {
                    case "audio/mpeg", "audio/mp3" -> "mp3";
                    case "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a";
                    case "audio/aac" -> "aac";
                    case "audio/ogg" -> "ogg";
                    case "audio/wav", "audio/x-wav" -> "wav";
                    case "audio/flac" -> "flac";
                    case "audio/webm", "video/webm" -> "webm";
                    case "image/png" -> "png";
                    case "image/jpeg" -> "jpg";
                    case "image/gif" -> "gif";
                    case "image/webp" -> "webp";
                    case "text/plain" -> "lrc";
                    case "video/mp4" -> "mp4";
                    case "video/quicktime" -> "mov";
                    default -> "bin";
                };
        return intent.candidateAssetId() + "." + ext;
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void requireTransport(
            MediaUploadOperation.Intent intent, MediaUploadTransport transport) {
        try {
            intent.operationFolder().requireWithin(settings.rootFolder);
            if (intent.operationType() != MediaUploadOperationType.UPLOAD_AND_BIND
                    || intent.transportMode() != transport) throw rejected();
        } catch (IllegalArgumentException invalid) {
            throw rejected();
        }
    }

    private static MediaUploadStorageException rejected() {
        return new MediaUploadStorageException(
                MediaUploadStorageException.Outcome.REJECTED_BEFORE_WRITE,
                MediaUploadErrorCode.PROVIDER_REJECTED);
    }

    private static final class BoundedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() {
            return result;
        }

        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 1048576 - output.size()) {
                    subscription.cancel();
                    result.completeExceptionally(
                            new IllegalStateException("Response exceeded limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                output.writeBytes(chunk);
            }
            subscription.request(1);
        }

        public void onError(Throwable ignored) {
            result.completeExceptionally(new IllegalStateException("Response unavailable"));
        }

        public void onComplete() {
            result.complete(output.toByteArray());
        }
    }
}
