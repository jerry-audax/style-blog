package com.blogsystem.infrastructure.gateway.imgbed.asset;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadCandidate;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadErrorCode;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadReceipt;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStorageException;
import com.blogsystem.infrastructure.configuration.asset.MediaUploadProviderSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Strict provider evidence normalization; no provider payload survives this boundary. */
public final class MediaUploadProviderResponseParser {
    private final MediaUploadProviderSettings settings;

    public MediaUploadProviderResponseParser(
            MediaUploadProviderSettings settings, ObjectMapper json) {
        this.settings = settings;
    }

    public MediaUploadReceipt receipt(JsonNode body, MediaUploadOperation.Intent intent) {
        try {
            if (!body.isArray() || body.size() != 1 || !body.get(0).path("src").isTextual())
                throw new IllegalArgumentException();
            URI source = URI.create(body.get(0).get("src").textValue());
            if (source.getRawQuery() != null
                    || source.getRawFragment() != null
                    || source.getRawUserInfo() != null
                    || source.getRawAuthority() != null && !source.isAbsolute())
                throw new IllegalArgumentException();
            if (source.isAbsolute()
                    && (!Objects.equals(source.getScheme(), settings.baseUrl.getScheme())
                            || !Objects.equals(
                                    source.getAuthority(), settings.baseUrl.getAuthority())))
                throw new IllegalArgumentException();
            String path = source.getRawPath();
            if (path.startsWith("file/")) path = "/" + path;
            if (!path.startsWith("/file/")) throw new IllegalArgumentException();
            MediaObjectKey key = new MediaObjectKey(decodeOnce(path.substring(6)));
            key.requireWithin(intent.operationFolder().value());
            return new MediaUploadReceipt(key);
        } catch (RuntimeException ignored) {
            throw unknown();
        }
    }

    public String initialized(JsonNode body) {
        if (!body.path("success").isBoolean() || !body.get("success").booleanValue())
            throw unknown();
        return session(body.path("uploadId").isTextual() ? body.get("uploadId").textValue() : null);
    }

    public void chunk(JsonNode body, String uploadId, int index) {
        if (!body.path("success").isBoolean()
                || !body.get("success").booleanValue()
                || !body.path("uploadId").isTextual()
                || !uploadId.equals(body.get("uploadId").textValue())
                || !body.path("chunkIndex").isIntegralNumber()
                || !body.get("chunkIndex").canConvertToInt()
                || body.get("chunkIndex").intValue() != index) throw unknown();
    }

    static String session(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw unknown();
        return id;
    }

    public record Page(long total, List<MediaUploadCandidate> candidates, String indexVersion) {}

    public Page page(JsonNode body, MediaUploadOperation.Intent intent) {
        try {
            JsonNode files = body.path("files"), total = body.path("totalCount");
            if (!files.isArray()
                    || files.size() > 2
                    || !total.isIntegralNumber()
                    || !total.canConvertToLong()
                    || total.longValue() < files.size()) throw new IllegalArgumentException();
            if (body.has("returnedCount")
                    && (!body.get("returnedCount").isIntegralNumber()
                            || body.get("returnedCount").longValue() != files.size()))
                throw new IllegalArgumentException();
            List<MediaUploadCandidate> candidates = new ArrayList<>();
            for (JsonNode file : files) {
                if (!file.path("name").isTextual()) throw new IllegalArgumentException();
                MediaObjectKey key = new MediaObjectKey(file.get("name").textValue());
                key.requireWithin(intent.operationFolder().value());
                JsonNode metadata = file.path("metadata");
                String channel = metadata.path("Channel").asText();
                if (!Set.of("telegram", "TelegramNew").contains(channel)
                        || !metadata.path("ChannelName").isTextual()
                        || !settings.channelName.equals(metadata.get("ChannelName").textValue()))
                    throw new IllegalArgumentException();
                JsonNode type =
                        metadata.has("File-Mime")
                                ? metadata.get("File-Mime")
                                : metadata.path("FileType");
                if (!type.isTextual() || !intent.file().mediaType().equals(type.textValue()))
                    throw new IllegalArgumentException();
                JsonNode sizeNode =
                        metadata.has("FileSizeBytes")
                                ? metadata.get("FileSizeBytes")
                                : metadata.path("File-Size");
                long size;
                if (sizeNode.isIntegralNumber() && sizeNode.canConvertToLong())
                    size = sizeNode.longValue();
                else if (sizeNode.isTextual() && sizeNode.textValue().matches("0|[1-9][0-9]*"))
                    size = Long.parseLong(sizeNode.textValue());
                else throw new IllegalArgumentException();
                if (size != intent.file().size()) throw new IllegalArgumentException();
                candidates.add(
                        new MediaUploadCandidate(
                                key,
                                intent.target().slot().kind(),
                                size,
                                "telegram",
                                settings.channelName));
            }
            String index =
                    body.has("indexLastUpdated") ? body.get("indexLastUpdated").asText() : null;
            return new Page(total.longValue(), List.copyOf(candidates), index);
        } catch (RuntimeException ignored) {
            throw unknown();
        }
    }

    private static String decodeOnce(String raw) {
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < raw.length(); ) {
            if (raw.charAt(i) != '%') {
                decoded.append(raw.charAt(i++));
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (i < raw.length() && raw.charAt(i) == '%') {
                if (i + 2 >= raw.length()) throw new IllegalArgumentException();
                int a = Character.digit(raw.charAt(i + 1), 16),
                        b = Character.digit(raw.charAt(i + 2), 16);
                if (a < 0 || b < 0) throw new IllegalArgumentException();
                int value = a * 16 + b;
                if (value == 47 || value == 92) throw new IllegalArgumentException();
                bytes.write(value);
                i += 3;
            }
            try {
                decoded.append(
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes.toByteArray())));
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException();
            }
        }
        return decoded.toString();
    }

    static MediaUploadStorageException unknown() {
        return new MediaUploadStorageException(
                MediaUploadStorageException.Outcome.UNKNOWN, MediaUploadErrorCode.PROVIDER_UNKNOWN);
    }
}
