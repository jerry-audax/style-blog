package com.blogsystem.domain.asset.inventory.model.query;

import com.blogsystem.domain.music.model.MusicTrack;
import com.blogsystem.domain.surprise.model.SurpriseVideo;

import java.util.List;
import java.util.Map;

public record MediaImportBatch(
        String provider,
        String root,
        List<MediaInventoryObject> objects,
        List<MusicTrack> tracks,
        List<SurpriseVideo> videos,
        Map<String, String> artwork,
        boolean videoSnapshotMissing) {
    public MediaImportBatch {
        objects = List.copyOf(objects);
        tracks = List.copyOf(tracks);
        videos = List.copyOf(videos);
        artwork = Map.copyOf(artwork);
    }
}
