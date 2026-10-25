package com.blogsystem.trigger.http.surprise;

import cn.dev33.satoken.annotation.SaCheckRole;

import com.blogsystem.application.surprise.port.SurpriseVideoUseCase;
import com.blogsystem.application.surprise.result.SurpriseVideoAdminView;
import com.blogsystem.application.surprise.service.SurpriseVideoApplicationService;
import com.blogsystem.domain.surprise.model.SurpriseVideoUpload;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/admin/surprise/videos")
@SaCheckRole("BLOG_OWNER")
public class SurpriseAdminController {
    private final SurpriseVideoUseCase service;

    public SurpriseAdminController(SurpriseVideoUseCase service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<java.util.List<SurpriseVideoAdminView>>> list() {
        return response(service.adminCatalog());
    }

    public record Metadata(
            String title,
            Boolean enabled,
            @JsonDeserialize(using = CatalogSortOrderDeserializer.class) Integer sortOrder,
            @JsonDeserialize(using = ExpectedVersionDeserializer.class) Long expectedVersion) {
        public Metadata(String title, Boolean enabled, Integer sortOrder) {
            this(title, enabled, sortOrder, null);
        }
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<SurpriseVideoApplicationService.VideoView>> create(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "惊喜视频") String title,
            @RequestParam(defaultValue = "true") boolean enabled,
            @RequestParam(defaultValue = "0") int sortOrder)
            throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("请选择视频");
        return response(
                service.create(
                        title,
                        enabled,
                        sortOrder,
                        new SurpriseVideoUpload(
                                file.getBytes(), type(file), file.getOriginalFilename())));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SurpriseVideoAdminView>> update(
            @PathVariable String id, @RequestBody Metadata metadata) {
        return response(
                service.updateMetadata(
                        id,
                        metadata.title(),
                        metadata.enabled(),
                        metadata.sortOrder(),
                        metadata.expectedVersion()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok());
    }

    private static String type(MultipartFile file) {
        String supplied = file.getContentType();
        if (supplied != null && supplied.startsWith("video/")) return supplied;
        String name =
                file.getOriginalFilename() == null
                        ? ""
                        : file.getOriginalFilename().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".mov")) return "video/quicktime";
        if (name.endsWith(".m4v")) return "video/x-m4v";
        return supplied == null ? "video/mp4" : supplied;
    }

    private <T> ResponseEntity<ApiResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
