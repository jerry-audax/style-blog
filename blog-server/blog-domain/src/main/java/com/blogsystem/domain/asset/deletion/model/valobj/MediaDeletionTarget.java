package com.blogsystem.domain.asset.deletion.model.valobj;

import java.util.Objects;

public record MediaDeletionTarget(Kind kind, String id, Slot slot) {
    public enum Kind {
        MUSIC_TRACK,
        MUSIC_MEDIA,
        SURPRISE_VIDEO,
        IMAGE_ASSET
    }

    public enum Slot {
        AUDIO,
        COVER,
        LYRICS
    }

    public MediaDeletionTarget {
        Objects.requireNonNull(kind);
        if (id == null
                || id.isBlank()
                || id.length() > 64
                || id.chars().anyMatch(Character::isISOControl)
                || (kind == Kind.MUSIC_MEDIA) != (slot != null))
            throw new IllegalArgumentException("无效删除目标");
    }
}
