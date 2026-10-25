package com.blogsystem.trigger.http.asset;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;

import com.blogsystem.application.asset.image.service.ImageApplicationService;
import com.blogsystem.domain.asset.image.model.query.ImagePage;
import com.blogsystem.domain.asset.image.model.valobj.ImagePurpose;
import com.blogsystem.domain.asset.image.model.valobj.ImageUpload;
import com.blogsystem.domain.asset.image.model.valobj.StoredImage;
import com.blogsystem.trigger.http.support.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
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
    public ApiResponse<StoredImage> avatar(@RequestPart("file") MultipartFile file)
            throws IOException {
        return upload(file, ImagePurpose.AVATAR);
    }

    @PostMapping("/article")
    @SaCheckRole("BLOG_OWNER")
    public ApiResponse<StoredImage> article(@RequestPart("file") MultipartFile file)
            throws IOException {
        return upload(file, ImagePurpose.ARTICLE);
    }

    @PostMapping("/cover")
    @SaCheckRole("BLOG_OWNER")
    public ApiResponse<StoredImage> cover(@RequestPart("file") MultipartFile file)
            throws IOException {
        return upload(file, ImagePurpose.COVER);
    }

    @GetMapping
    @SaCheckRole("BLOG_OWNER")
    public ApiResponse<ImagePage> list(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int count,
            @RequestParam(defaultValue = "") String search) {
        return ApiResponse.ok(images.list(offset, count, search));
    }

    public record DeleteImageRequest(@NotBlank String path) {}

    @DeleteMapping
    @SaCheckRole("BLOG_OWNER")
    public ApiResponse<Void> delete(@RequestBody @Valid DeleteImageRequest request) {
        images.delete(request.path());
        return ApiResponse.ok();
    }

    private ApiResponse<StoredImage> upload(MultipartFile file, ImagePurpose purpose)
            throws IOException {
        int max = (purpose == ImagePurpose.AVATAR ? 2 : 10) * 1024 * 1024;
        if (file.isEmpty() || file.getSize() > max)
            throw new IllegalArgumentException("图片为空或超过大小限制");
        return ApiResponse.ok(
                images.upload(
                        new ImageUpload(file.getBytes(), file.getContentType(), purpose),
                        StpUtil.getLoginIdAsLong()));
    }
}
