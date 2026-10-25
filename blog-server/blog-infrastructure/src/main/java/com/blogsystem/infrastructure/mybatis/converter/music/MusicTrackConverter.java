package com.blogsystem.infrastructure.mybatis.converter.music;

import com.blogsystem.domain.music.model.MusicTrackSnapshot;
import com.blogsystem.domain.music.model.PersistedMusicTrack;
import com.blogsystem.infrastructure.mybatis.record.music.MusicTrackRecord;

/** Maps persisted catalogue facts without querying or changing eligibility. */
public final class MusicTrackConverter {
    private MusicTrackConverter() {}

    public static MusicTrackSnapshot toSnapshot(MusicTrackRecord row) {
        return new MusicTrackSnapshot(
                new PersistedMusicTrack(
                        row.id(),
                        row.title(),
                        row.artist(),
                        row.sourceId(),
                        row.audioAssetId(),
                        row.coverAssetId(),
                        row.lyricsAssetId(),
                        row.externalCoverUrl(),
                        row.enabled(),
                        row.sortOrder()),
                row.version(),
                row.createdAt().toInstant(),
                row.updatedAt().toInstant());
    }
}
