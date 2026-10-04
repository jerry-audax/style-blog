package com.blogsystem.music.interfaces.web;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.blogsystem.music.application.MusicApplicationService;
import com.blogsystem.music.application.MusicCatalogApplicationService;
import com.blogsystem.music.domain.model.*;
import com.blogsystem.shared.interfaces.http.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/admin/music")
@SaCheckRole("ADMIN")
public class MusicAdminController {
    private final MusicApplicationService music;
    private final MusicCatalogApplicationService catalog;

    public MusicAdminController(MusicApplicationService music) {
        this(music, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MusicAdminController(MusicApplicationService music, MusicCatalogApplicationService catalog) {
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
    public ResponseEntity<ApiResponse<java.util.List<MusicCatalogApplicationService.TrackView>>> catalog() {
        return response(requireCatalog().catalog());
    }

    public record TrackRequest(String id, String title, String artist, String sourceId, Boolean enabled, Integer sortOrder) { }

    @PostMapping("/tracks")
    public ResponseEntity<ApiResponse<MusicCatalogApplicationService.TrackView>> create(@RequestBody TrackRequest request) {
        return response(requireCatalog().saveMetadata(request.id(), request.title(), request.artist(), request.sourceId(),
                request.enabled() == null || request.enabled(), request.sortOrder() == null ? 0 : request.sortOrder()));
    }

    @PutMapping("/tracks/{id}")
    public ResponseEntity<ApiResponse<MusicCatalogApplicationService.TrackView>> update(@PathVariable String id, @RequestBody TrackRequest request) {
        return response(requireCatalog().saveMetadata(id, request.title(), request.artist(), request.sourceId(),
                request.enabled() == null || request.enabled(), request.sortOrder() == null ? 0 : request.sortOrder()));
    }

    @DeleteMapping("/tracks/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTrack(@PathVariable String id) {
        requireCatalog().deleteTrack(id);
        return response(null);
    }

    @DeleteMapping("/tracks/{id}/media/{kind}")
    public ResponseEntity<ApiResponse<MusicCatalogApplicationService.TrackView>> deleteMedia(@PathVariable String id, @PathVariable String kind) {
        return response(requireCatalog().deleteMedia(id, parseKind(kind)));
    }

    @PostMapping("/tracks/{id}/media/{kind}")
    public ResponseEntity<ApiResponse<MusicCatalogApplicationService.TrackView>> uploadMedia(@PathVariable String id,
                                                                                              @PathVariable String kind,
                                                                                              @RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new MusicException(MusicException.Reason.INVALID_REQUEST);
        MusicMediaKind mediaKind = parseKind(kind);
        try {
            String contentType = file.getContentType();
            if (contentType == null || contentType.equalsIgnoreCase("application/octet-stream"))
                contentType = inferType(file.getOriginalFilename(), mediaKind);
            return response(requireCatalog().upload(id, new MusicMediaUpload(file.getBytes(),
                    contentType, file.getOriginalFilename(), mediaKind)));
        } catch (IllegalArgumentException error) {
            throw new MusicException(MusicException.Reason.INVALID_REQUEST);
        }
    }

    private MusicCatalogApplicationService requireCatalog() {
        if (catalog == null) throw new MusicException(MusicException.Reason.NOT_CONFIGURED);
        return catalog;
    }

    private static MusicMediaKind parseKind(String value) {
        try { return MusicMediaKind.valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (RuntimeException error) { throw new MusicException(MusicException.Reason.INVALID_REQUEST); }
    }

    private static String fallbackType(MusicMediaKind kind) {
        return switch (kind) { case AUDIO -> "audio/mpeg"; case COVER -> "image/jpeg"; case LYRICS -> "text/plain"; };
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
