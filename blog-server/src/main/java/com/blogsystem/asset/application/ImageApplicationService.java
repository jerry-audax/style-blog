package com.blogsystem.asset.application;

import com.blogsystem.asset.domain.model.*;
import com.blogsystem.asset.domain.repository.ImageRepository;
import org.springframework.stereotype.Service;

@Service
public class ImageApplicationService {
    private final ImageRepository repository;

    public ImageApplicationService(ImageRepository repository) {
        this.repository = repository;
    }

    public StoredImage upload(ImageUpload image, long authenticatedUserId) {
        if (authenticatedUserId <= 0) throw new IllegalArgumentException("无效用户");
        return repository.upload(image, authenticatedUserId);
    }

    public ImagePage list(int offset, int count, String search) {
        if (offset < 0 || offset > 100000 || count < 1 || count > 50 || search == null || search.length() > 100 ||
                search.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("无效查询参数");
        return repository.list(offset, count, search.trim());
    }

    public void delete(String path) {
        repository.delete(new ImagePath(path));
    }
}
