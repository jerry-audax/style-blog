package com.blogsystem.asset.interfaces.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.blogsystem.asset.application.ImageApplicationService;
import com.blogsystem.asset.domain.model.*;
import com.blogsystem.shared.interfaces.http.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/images")
@SaCheckLogin
public class ImageController {
    private final ImageApplicationService images;

    public ImageController(ImageApplicationService images) {
        this.images = images;
    }

    @PostMapping("/avatar")
    public ApiResponse<StoredImage> avatar(@RequestPart("file") MultipartFile file) throws IOException {
        return upload(file, ImagePurpose.AVATAR);
    }

    @PostMapping("/article")
    @SaCheckRole("ADMIN")
    public ApiResponse<StoredImage> article(@RequestPart("file") MultipartFile file) throws IOException {
        return upload(file, ImagePurpose.ARTICLE);
    }

    @PostMapping("/cover")
    @SaCheckRole("ADMIN")
    public ApiResponse<StoredImage> cover(@RequestPart("file") MultipartFile file) throws IOException {
        return upload(file, ImagePurpose.COVER);
    }

    @GetMapping
    @SaCheckRole("ADMIN")
    public ApiResponse<ImagePage> list(@RequestParam(defaultValue = "0") int offset,
                                       @RequestParam(defaultValue = "20") int count,
                                       @RequestParam(defaultValue = "") String search) {
        return ApiResponse.ok(images.list(offset, count, search));
    }

    public record DeleteImageRequest(@NotBlank String path) {
    }

    @DeleteMapping
    @SaCheckRole("ADMIN")
    public ApiResponse<Void> delete(@RequestBody @Valid DeleteImageRequest request) {
        images.delete(request.path());
        return ApiResponse.ok();
    }

    private ApiResponse<StoredImage> upload(MultipartFile file, ImagePurpose purpose) throws IOException {
        int max = (purpose == ImagePurpose.AVATAR ? 2 : 10) * 1024 * 1024;
        if (file.isEmpty() || file.getSize() > max) throw new IllegalArgumentException("图片为空或超过大小限制");
        return ApiResponse.ok(images.upload(new ImageUpload(file.getBytes(), file.getContentType(), purpose), StpUtil.getLoginIdAsLong()));
    }
}
