package com.blogsystem.application.asset.inventory.service;

import com.blogsystem.application.asset.inventory.service.MediaReferenceSnapshotApplicationService;

import org.junit.jupiter.api.Assertions;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.inventory.model.query.MediaReference;
import com.blogsystem.domain.asset.inventory.gateway.MediaReferenceSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaReferenceSnapshotApplicationServiceTest {
    @Test
    void collectsImmutableReferencesFromAllSourcesWithoutWriting() {
        MediaReference audio = new MediaReference(
                MediaKind.AUDIO,
                new com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey("blog/music/a.mp3"),
                "music_track", "track-1", "Track audio");
        MediaReference cover = new MediaReference(
                MediaKind.IMAGE,
                new com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey("blog/covers/a.jpg"),
                "music_track", "track-1", "Track cover");
        MediaReferenceSnapshotApplicationService service =
                new MediaReferenceSnapshotApplicationService(List.of(
                        () -> List.of(audio),
                        () -> List.of(cover)));

        List<MediaReference> result = service.collect();

        assertEquals(List.of(audio, cover), result);
        assertThrows(UnsupportedOperationException.class,
                () -> result.add(audio));
    }

    @Test
    void rejectsNullSourcesAndNullSourceResults() {
        assertThrows(NullPointerException.class,
                () -> new MediaReferenceSnapshotApplicationService(null));
        MediaReferenceSource nullSource = () -> null;
        MediaReferenceSnapshotApplicationService service =
                new MediaReferenceSnapshotApplicationService(List.of(nullSource));
        assertThrows(IllegalStateException.class, service::collect);
    }
}
