package com.blogsystem.trigger.http.music;

import cn.dev33.satoken.annotation.SaCheckRole;

import com.blogsystem.application.music.port.MusicCatalogUseCase;
import com.blogsystem.application.music.service.MusicApplicationService;
import com.blogsystem.application.music.service.MusicCatalogApplicationService;
import com.blogsystem.domain.music.model.MusicAccount;
import com.blogsystem.domain.music.model.MusicException;
import com.blogsystem.domain.music.model.MusicMediaKind;
import com.blogsystem.domain.music.model.MusicMediaUpload;
import com.blogsystem.domain.music.model.MusicPlaylist;
import com.blogsystem.trigger.http.support.ApiResponse;
import com.blogsystem.trigger.http.support.CatalogSortOrderDeserializer;
import com.blogsystem.trigger.http.support.ExpectedVersionDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/admin/music")
@SaCheckRole("BLOG_OWNER")
public class MusicAdminController {
    private final MusicApplicationService music;
    private final MusicCatalogUseCase catalog;

    public MusicAdminController(MusicApplicationService music) {
        this(music, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MusicAdminController(MusicApplicationService music, MusicCatalogUseCase catalog) {
        this.music = music;
        this.catalog = catalog;
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<MusicApplicationService.Status>> status() {
        return response(music.status());
    }

    @GetMapping("/playlist")
    public ResponseEntity<ApiResponse<MusicPlaylist>> playlist() {
        return response(music.playlist().orElse(null));
    }

    @PostMapping("/authorize")
    public ResponseEntity<ApiResponse<MusicAccount.Authorization>> authorize() {
        return response(music.authorize());
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<MusicPlaylist>> sync() {
        return response(music.synchronize());
    }

    @DeleteMapping("/authorization")
    public ResponseEntity<ApiResponse<MusicAccount.Revocation>> revoke() {
        return response(music.revoke());
    }

    @GetMapping("/catalog")
    public ResponseEntity<ApiResponse<java.util.List<MusicCatalogApplicationService.TrackView>>>
            catalog() {
        return response(requireCatalog().catalog());
    }

    public record TrackRequest(
            String id,
            String title,
            String artist,
            String sourceId,
            Boolean enabled,
            @JsonDeserialize(using = CatalogSortOrderDeserializer.class) Integer sortOrder,
            @JsonDeserialize(using = ExpectedVersionDeserializer.class) Long expectedVersion) {
        public TrackRequest(
                String id,
                String title,
                String artist,
                String sourceId,
                Boolean enabled,
                Integer sortOrder) {
            this(id, title, artist, sourceId, enabled, sortOrder, null);
        }
    }

    @PostMapping("/tracks")
    public ResponseEntity<ApiResponse<MusicCatalogApplicationService.TrackView>> create(
            @RequestBody TrackRequest request) {
        return response(
                requireCatalog()
                        .createMetadata(
                                request.id(),
                                request.title(),
                                request.artist(),
                                request.sourceId(),
                                request.enabled() == null || request.enabled(),
                                request.sortOrder() == null ? 0 : request.sortOrder()));
    }

    @PutMapping("/tracks/{id}")
    public ResponseEntity<ApiResponse<MusicCatalogApplicationService.TrackView>> update(
            @PathVariable String id, @RequestBody TrackRequest request) {
        return response(
                requireCatalog()
                        .updateMetadata(
                                id,
                                request.title(),
                                request.artist(),
                                request.sourceId(),
                                request.enabled() == null || request.enabled(),
                                request.sortOrder() == null ? 0 : request.sortOrder(),
                                request.expectedVersion()));
    }

    @DeleteMapping("/tracks/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTrack(@PathVariable String id) {
        requireCatalog().deleteTrack(id);
        return response(null);
    }

    @DeleteMapping("/tracks/{id}/media/{kind}")
    public ResponseEntity<ApiResponse<MusicCatalogApplicationService.TrackView>> deleteMedia(
            @PathVariable String id, @PathVariable String kind) {
        return response(requireCatalog().deleteMedia(id, parseKind(kind)));
    }

    @PostMapping("/tracks/{id}/media/{kind}")
    public ResponseEntity<ApiResponse<MusicCatalogApplicationService.TrackView>> uploadMedia(
            @PathVariable String id,
            @PathVariable String kind,
            @RequestPart("file") MultipartFile file)
            throws IOException {
        if (file.isEmpty()) throw new MusicException(MusicException.Reason.INVALID_REQUEST);
        MusicMediaKind mediaKind = parseKind(kind);
        try {
            String contentType = file.getContentType();
            if (contentType == null || contentType.equalsIgnoreCase("application/octet-stream"))
                contentType = inferType(file.getOriginalFilename(), mediaKind);
            return response(
                    requireCatalog()
                            .upload(
                                    id,
                                    new MusicMediaUpload(
                                            file.getBytes(),
                                            contentType,
                                            file.getOriginalFilename(),
                                            mediaKind)));
        } catch (IllegalArgumentException error) {
            throw new MusicException(MusicException.Reason.INVALID_REQUEST);
        }
    }

    private MusicCatalogUseCase requireCatalog() {
        if (catalog == null) throw new MusicException(MusicException.Reason.NOT_CONFIGURED);
        return catalog;
    }

    private static MusicMediaKind parseKind(String value) {
        try {
            return MusicMediaKind.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException error) {
            throw new MusicException(MusicException.Reason.INVALID_REQUEST);
        }
    }

    private static String fallbackType(MusicMediaKind kind) {
        return switch (kind) {
            case AUDIO -> "audio/mpeg";
            case COVER -> "image/jpeg";
            case LYRICS -> "text/plain";
        };
    }

    private static String inferType(String filename, MusicMediaKind kind) {
        String lower = filename == null ? "" : filename.toLowerCase(java.util.Locale.ROOT);
        if (kind == MusicMediaKind.LYRICS) return "text/plain";
        if (kind == MusicMediaKind.COVER) {
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".webp")) return "image/webp";
            return "image/jpeg";
        }
        if (lower.endsWith(".m4a")) return "audio/x-m4a";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".ogg") || lower.endsWith(".oga")) return "audio/ogg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".webm")) return "audio/webm";
        return fallbackType(kind);
    }

    private <T> ResponseEntity<ApiResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
