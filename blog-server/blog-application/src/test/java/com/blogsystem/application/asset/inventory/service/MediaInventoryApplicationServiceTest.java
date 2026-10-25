package com.blogsystem.application.asset.inventory.service;

import com.blogsystem.application.asset.inventory.service.MediaInventoryApplicationService;

import org.junit.jupiter.api.Assertions;

import com.blogsystem.domain.asset.inventory.model.query.MediaInventoryObject;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaMetadata;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.inventory.model.valobj.MediaInventoryException;
import com.blogsystem.domain.asset.inventory.gateway.MediaInventoryGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaInventoryApplicationServiceTest {
    @Test
    void previewAggregatesRequestedKindsWithoutMutatingProvider() {
        MediaInventoryApplicationService service = new MediaInventoryApplicationService(kind -> switch (kind) {
            case IMAGE -> List.of(item(MediaKind.IMAGE, "blog/images/a.png"));
            case AUDIO -> List.of(item(MediaKind.AUDIO, "blog/music/a.mp3"), item(MediaKind.AUDIO, "blog/music/b.mp3"));
            default -> List.of();
        });

        var preview = service.preview(Set.of(MediaKind.IMAGE, MediaKind.AUDIO));

        assertEquals(1, preview.count(MediaKind.IMAGE));
        assertEquals(2, preview.count(MediaKind.AUDIO));
        assertEquals(3, preview.total());
    }

    @Test
    void previewRejectsTheSameProviderPathReportedUnderTwoKinds() {
        MediaInventoryGateway duplicate = kind -> List.of(item(kind, "blog/shared/object.bin"));
        MediaInventoryApplicationService service = new MediaInventoryApplicationService(duplicate);

        assertThrows(MediaInventoryException.class,
                () -> service.preview(Set.of(MediaKind.IMAGE, MediaKind.VIDEO)));
    }

    @Test
    void previewRejectsNullProviderResponsesAndEntries() {
        MediaInventoryApplicationService nullList = new MediaInventoryApplicationService(kind -> null);
        assertThrows(MediaInventoryException.class,
                () -> nullList.preview(Set.of(MediaKind.IMAGE)));

        MediaInventoryApplicationService nullEntry = new MediaInventoryApplicationService(kind -> Collections.singletonList(null));
        assertThrows(MediaInventoryException.class,
                () -> nullEntry.preview(Set.of(MediaKind.IMAGE)));
    }

    private static MediaInventoryObject item(MediaKind kind, String path) {
        return new MediaInventoryObject(kind, new MediaObjectKey(path),
                new MediaMetadata(path.substring(path.lastIndexOf('/') + 1), null, null, null, null, null, null));
    }
}
