-- MEDIA-01A: media metadata and business-directory tables.
-- This is an additive migration. It intentionally has no DROP, TRUNCATE,
-- IF NOT EXISTS, credentials, or seed data.

CREATE TABLE media_asset (
    id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    media_kind VARCHAR(16) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(128),
    file_size BIGINT,
    sha256 CHAR(64),
    width INTEGER,
    height INTEGER,
    duration_ms BIGINT,
    status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_media_asset PRIMARY KEY (id),
    CONSTRAINT uk_media_asset_location UNIQUE (provider, object_key),
    CONSTRAINT ck_media_asset_provider_non_blank CHECK (btrim(provider) <> ''),
    CONSTRAINT ck_media_asset_object_key_non_blank CHECK (btrim(object_key) <> ''),
    CONSTRAINT ck_media_asset_kind CHECK (media_kind IN ('IMAGE', 'AUDIO', 'VIDEO', 'LYRICS')),
    CONSTRAINT ck_media_asset_file_size CHECK (file_size IS NULL OR file_size >= 0),
    CONSTRAINT ck_media_asset_sha256 CHECK (sha256 IS NULL OR btrim(sha256) ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_media_asset_width CHECK (width IS NULL OR width > 0),
    CONSTRAINT ck_media_asset_height CHECK (height IS NULL OR height > 0),
    CONSTRAINT ck_media_asset_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT ck_media_asset_status CHECK (status IN ('ACTIVE', 'DELETE_PENDING', 'DELETE_FAILED', 'DELETED')),
    CONSTRAINT ck_media_asset_version CHECK (version >= 0),
    CONSTRAINT ck_media_asset_time_order CHECK (updated_at >= created_at)
);

CREATE INDEX ix_media_asset_kind_status_created
    ON media_asset (media_kind, status, created_at);

CREATE TABLE music_track (
    id VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    artist VARCHAR(200) NOT NULL,
    source_id VARCHAR(64),
    audio_asset_id UUID,
    cover_asset_id UUID,
    lyrics_asset_id UUID,
    external_cover_url TEXT,
    enabled BOOLEAN NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_music_track PRIMARY KEY (id),
    CONSTRAINT ck_music_track_title_non_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_music_track_sort_order CHECK (sort_order BETWEEN 0 AND 100000),
    CONSTRAINT ck_music_track_version CHECK (version >= 0),
    CONSTRAINT ck_music_track_time_order CHECK (updated_at >= created_at),
    CONSTRAINT fk_music_track_audio_asset FOREIGN KEY (audio_asset_id)
        REFERENCES media_asset (id) ON DELETE NO ACTION,
    CONSTRAINT fk_music_track_cover_asset FOREIGN KEY (cover_asset_id)
        REFERENCES media_asset (id) ON DELETE NO ACTION,
    CONSTRAINT fk_music_track_lyrics_asset FOREIGN KEY (lyrics_asset_id)
        REFERENCES media_asset (id) ON DELETE NO ACTION
);

CREATE INDEX ix_music_track_audio_asset ON music_track (audio_asset_id);
CREATE INDEX ix_music_track_cover_asset ON music_track (cover_asset_id);
CREATE INDEX ix_music_track_lyrics_asset ON music_track (lyrics_asset_id);

CREATE TABLE surprise_video (
    id VARCHAR(64) NOT NULL,
    title VARCHAR(120) NOT NULL,
    video_asset_id UUID NOT NULL,
    enabled BOOLEAN NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_surprise_video PRIMARY KEY (id),
    CONSTRAINT ck_surprise_video_title_non_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_surprise_video_sort_order CHECK (sort_order BETWEEN 0 AND 100000),
    CONSTRAINT ck_surprise_video_version CHECK (version >= 0),
    CONSTRAINT ck_surprise_video_time_order CHECK (updated_at >= created_at),
    CONSTRAINT fk_surprise_video_asset FOREIGN KEY (video_asset_id)
        REFERENCES media_asset (id) ON DELETE NO ACTION
);

CREATE INDEX ix_surprise_video_asset ON surprise_video (video_asset_id);
