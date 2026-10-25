package com.blogsystem.trigger.http.asset;

import com.blogsystem.application.asset.publication.service.MediaPublicationMachineApplicationService;
import com.blogsystem.domain.asset.publication.model.valobj.MediaPublicationManifestInput;
import com.blogsystem.trigger.http.asset.dto.MediaPublicationMachineRequests;
import com.blogsystem.trigger.http.asset.dto.MediaPublicationPinView;
import com.blogsystem.trigger.http.support.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/media-publications")
@ConditionalOnProperty(name = "media.catalog.publication-pins.enabled", havingValue = "true")
public final class MediaPublicationMachineController {
    private final MediaPublicationMachineApplicationService publication;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private final ObjectMapper json = strictJson();

    public MediaPublicationMachineController(MediaPublicationMachineApplicationService publication) { this.publication = publication; }

    @PostMapping("/prepare") public ApiResponse<MediaPublicationPinView> prepare(HttpServletRequest request) {
        var body = read(request, MediaPublicationMachineRequests.Prepare.class); protocol(body.protocolVersion());
        return ApiResponse.ok(MediaPublicationPinView.from(publication.prepare(body.releaseId(),
                new MediaPublicationManifestInput(body.sourceDigest(), body.links(), body.contents(), body.htmlContents()))));
    }

    @GetMapping("/{releaseId}") public ApiResponse<MediaPublicationPinView> get(@PathVariable String releaseId) {
        return ApiResponse.ok(MediaPublicationPinView.from(publication.get(releaseId)));
    }

    @PostMapping("/{releaseId}/verify") public ApiResponse<Map<String, Object>> verify(@PathVariable String releaseId, HttpServletRequest request) {
        var body = read(request, MediaPublicationMachineRequests.Verify.class); protocol(body.protocolVersion());
        publication.verify(releaseId, body.manifestDigest(), new MediaPublicationManifestInput(body.sourceDigest(), body.links(), body.contents(), body.htmlContents()));
        return ApiResponse.ok(Map.of("protocolVersion", 1, "verified", true));
    }

    @PostMapping("/{releaseId}/release") public ApiResponse<MediaPublicationPinView> release(@PathVariable String releaseId, HttpServletRequest request) {
        var body = read(request, MediaPublicationMachineRequests.Release.class); protocol(body.protocolVersion());
        if (!Boolean.TRUE.equals(body.retirementConfirmed()) || body.expectedVersion() == null
                || !body.expectedVersion().matches("0|[1-9][0-9]{0,18}")) throw new MediaPublicationRequestException(400);
        long version;
        try { version = Long.parseLong(body.expectedVersion()); } catch (NumberFormatException failure) { throw new MediaPublicationRequestException(400); }
        return ApiResponse.ok(MediaPublicationPinView.from(publication.release(releaseId, version, body.manifestDigest())));
    }

    private <T> T read(HttpServletRequest request, Class<T> type) {
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new MediaPublicationRequestException(415);
        try {
            if (request.getContentType() == null) throw new MediaPublicationRequestException(415);
            var contentType = MediaType.parseMediaType(request.getContentType());
            if (!contentType.getType().equalsIgnoreCase("application") || !contentType.getSubtype().equalsIgnoreCase("json"))
                throw new MediaPublicationRequestException(415);
        } catch (org.springframework.http.InvalidMediaTypeException failure) { throw new MediaPublicationRequestException(415); }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) throw new MediaPublicationRequestException(413);
        try {
            // 分块请求没有可信Content-Length；最多读取上限加一字节，再进行JSON解析。
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) throw new MediaPublicationRequestException(413);
            T result = json.readValue(body, type);
            if (result == null) throw new MediaPublicationRequestException(400);
            return result;
        }
        catch (IOException failure) { throw new MediaPublicationRequestException(400); }
    }

    private static ObjectMapper strictJson() {
        var json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
        for (var shape : new CoercionInputShape[]{CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean})
            json.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        return json;
    }

    private static void protocol(Integer value) { if (!Integer.valueOf(1).equals(value)) throw new MediaPublicationRequestException(400); }
}
