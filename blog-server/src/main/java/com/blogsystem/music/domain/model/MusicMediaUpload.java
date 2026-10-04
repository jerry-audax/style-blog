package com.blogsystem.music.domain.model;

import java.util.Locale;
import java.util.Set;

public record MusicMediaUpload(byte[] bytes, String mediaType, String filename, MusicMediaKind kind) {
    private static final Set<String> COVER_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    private static final Set<String> AUDIO_TYPES = HostedAudio.MEDIA_TYPES;

    public MusicMediaUpload {
        if (bytes == null || bytes.length == 0 || bytes.length > maxBytes(kind))
            throw new IllegalArgumentException("音乐文件为空或超过大小限制");
        if (kind == null || mediaType == null || filename == null || filename.isBlank())
            throw new IllegalArgumentException("音乐文件参数无效");
        String mime = mediaType.toLowerCase(Locale.ROOT);
        boolean lyrics = kind == MusicMediaKind.LYRICS && (mime.equals("text/plain") || mime.equals("application/octet-stream")
                || filename.toLowerCase(Locale.ROOT).endsWith(".lrc"));
        boolean valid = switch (kind) {
            case AUDIO -> AUDIO_TYPES.contains(mime);
            case COVER -> COVER_TYPES.contains(mime);
            case LYRICS -> lyrics;
        };
        if (!valid) throw new IllegalArgumentException("音乐文件类型不受支持");
        bytes = bytes.clone();
        mediaType = mime;
        filename = filename.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
        if (filename.isBlank() || filename.length() > 180) throw new IllegalArgumentException("音乐文件名无效");
    }

    private static int maxBytes(MusicMediaKind kind) {
        if (kind == null) return 0;
        return switch (kind) {
            case AUDIO -> 100 * 1024 * 1024;
            case COVER -> 10 * 1024 * 1024;
            case LYRICS -> 512 * 1024;
        };
    }
}
