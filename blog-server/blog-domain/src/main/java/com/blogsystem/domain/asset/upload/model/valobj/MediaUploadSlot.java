package com.blogsystem.domain.asset.upload.model.valobj;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;

public enum MediaUploadSlot {
    AUDIO(MediaKind.AUDIO, 104857600),
    COVER(MediaKind.IMAGE, 10485760),
    LYRICS(MediaKind.LYRICS, 524288),
    VIDEO(MediaKind.VIDEO, 209715200),
    IMAGE(MediaKind.IMAGE, 10485760),
    AVATAR(MediaKind.IMAGE, 2097152);
    private final MediaKind kind;
    private final long maximumBytes;

    MediaUploadSlot(MediaKind kind, long maximumBytes) {
        this.kind = kind;
        this.maximumBytes = maximumBytes;
    }

    public MediaKind kind() {
        return kind;
    }

    public long maximumBytes() {
        return maximumBytes;
    }
}
