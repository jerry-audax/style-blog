package com.blogsystem.application.asset.image.service;

import com.blogsystem.application.asset.image.service.ImageApplicationService;

import com.blogsystem.domain.asset.image.model.query.ImagePage;
import com.blogsystem.domain.asset.image.model.valobj.ImagePath;
import com.blogsystem.domain.asset.image.model.valobj.ImagePurpose;
import com.blogsystem.domain.asset.image.model.valobj.ImageUpload;
import com.blogsystem.domain.asset.image.model.valobj.StoredImage;

import org.junit.jupiter.api.Assertions;
import org.mockito.Mockito;

import com.blogsystem.domain.asset.image.gateway.ImageStorageGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageApplicationServiceTest {
    @Test
    void orchestratesThroughDomainRepositoryOnly() {
        var repository = mock(ImageStorageGateway.class);
        var service = new ImageApplicationService(repository);
        var input = new ImageUpload(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, "image/png", ImagePurpose.AVATAR);
        var stored = new StoredImage("blog/avatars/7/a.png", "https://images.test/file/blog/avatars/7/a.png", "image/png", 8);
        when(repository.upload(input, 7L)).thenReturn(stored);
        assertEquals(stored, service.upload(input, 7L));
        when(repository.list(0, 20, "")).thenReturn(new ImagePage(List.of(stored), 1));
        assertEquals(1, service.list(0, 20, "").total());
        service.delete("blog/avatars/7/a.png");
        verify(repository).delete(new ImagePath(stored.path()));
    }

    @Test
    void rejectsUnboundedPaginationAndSearchBeforeCallingProvider() {
        var repository = mock(ImageStorageGateway.class);
        var service = new ImageApplicationService(repository);
        assertThrows(IllegalArgumentException.class, () -> service.list(-1, 20, ""));
        assertThrows(IllegalArgumentException.class, () -> service.list(0, -1, ""));
        assertThrows(IllegalArgumentException.class, () -> service.list(0, 51, ""));
        assertThrows(IllegalArgumentException.class, () -> service.list(0, 20, "x".repeat(101)));
        verifyNoInteractions(repository);
    }
}
