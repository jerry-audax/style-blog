package com.blogsystem.surprise.interfaces.web;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.blogsystem.shared.interfaces.http.ApiResponse;
import com.blogsystem.surprise.application.SurpriseVideoApplicationService;
import com.blogsystem.surprise.domain.model.SurpriseVideoUpload;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@RestController
@RequestMapping("/api/admin/surprise/videos")
@SaCheckRole("ADMIN")
public class SurpriseAdminController {
    private final SurpriseVideoApplicationService service;
    public SurpriseAdminController(SurpriseVideoApplicationService service) { this.service = service; }
    @GetMapping
    public ResponseEntity<ApiResponse<java.util.List<SurpriseVideoApplicationService.VideoView>>> list() { return response(service.adminList()); }
    public record Metadata(String title, Boolean enabled, Integer sortOrder) { }
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<SurpriseVideoApplicationService.VideoView>> create(@RequestPart("file") MultipartFile file, @RequestParam(defaultValue = "惊喜视频") String title, @RequestParam(defaultValue = "true") boolean enabled, @RequestParam(defaultValue = "0") int sortOrder) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("请选择视频");
        return response(service.create(title, enabled, sortOrder, new SurpriseVideoUpload(file.getBytes(), type(file), file.getOriginalFilename())));
    }
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SurpriseVideoApplicationService.VideoView>> update(@PathVariable String id, @RequestBody Metadata metadata) { return response(service.update(id, metadata.title(), metadata.enabled(), metadata.sortOrder())); }
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) { service.delete(id); return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok()); }
    private static String type(MultipartFile file) {
        String supplied = file.getContentType();
        if (supplied != null && supplied.startsWith("video/")) return supplied;
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".mov")) return "video/quicktime";
        if (name.endsWith(".m4v")) return "video/x-m4v";
        return supplied == null ? "video/mp4" : supplied;
    }
    private <T> ResponseEntity<ApiResponse<T>> response(T data) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data)); }
}
