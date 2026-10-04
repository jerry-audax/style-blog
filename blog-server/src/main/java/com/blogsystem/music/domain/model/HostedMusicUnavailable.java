package com.blogsystem.music.domain.model;

/**
 * Do not retain upstream exception messages or response bodies, which may contain secrets.
 */
public class HostedMusicUnavailable extends RuntimeException {
    public HostedMusicUnavailable() {
        super("本站音频歌单暂时不可用，请稍后重试");
    }
}
