package com.blogsystem.application.music.service;

import com.blogsystem.application.asset.upload.service.MediaUploadApplicationService;
import com.blogsystem.domain.asset.upload.model.entity.MediaUploadOperation;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadContent;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadFile;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadRequest;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadSlot;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadStatus;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadTargetType;
import com.blogsystem.domain.asset.upload.repository.MediaUploadOperationRepository;
import com.blogsystem.domain.music.gateway.SongArtworkDownloadGateway;
import com.blogsystem.domain.music.model.MusicCatalogException;
import com.blogsystem.domain.music.adapter.repository.MusicTrackRepository;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Explicit owner action; public GET never fetches, uploads, or persists a cover. */
public final class MusicCoverBackfillApplicationService {
    private final MusicTrackRepository tracks;
    private final SongArtworkDownloadGateway artwork;
    private final MediaUploadOperationRepository operations;
    private final MediaUploadApplicationService uploads;

    public MusicCoverBackfillApplicationService(MusicTrackRepository tracks,
            SongArtworkDownloadGateway artwork, MediaUploadOperationRepository operations,
            MediaUploadApplicationService uploads) {
        this.tracks = tracks;
        this.artwork = artwork;
        this.operations = operations;
        this.uploads = uploads;
    }

    public MediaUploadOperation complete(long actor, String trackId, Long expectedVersion, UUID operationId) {
        if (expectedVersion == null) throw failure(MusicCatalogException.Reason.MISSING_VERSION);
        if (actor <= 0 || trackId == null || trackId.isBlank() || expectedVersion < 0 || operationId == null)
            throw failure(MusicCatalogException.Reason.INVALID_REQUEST);
        var original = operations.findOwned(actor, operationId);
        if (original.isPresent()) {
            var target = original.get().intent().target();
            if (target.type() != MediaUploadTargetType.MUSIC_TRACK || target.slot() != MediaUploadSlot.COVER
                    || !target.id().equals(trackId) || !Objects.equals(target.expectedVersion(), expectedVersion))
                throw new MediaUploadException(MediaUploadException.Reason.CONFLICT);
            // A READY intent has not dispatched bytes. Re-download and let create()
            // verify its frozen fingerprint before using the original content workflow.
            if (original.get().progress().status() != MediaUploadStatus.READY) return original.get();
        } else {
            // Includes manual uploads and survives unrelated metadata version changes.
            var unresolved = operations.findUnresolvedMusicCover(actor, trackId);
            if (unresolved.isPresent()) return unresolved.get();
        }
        var row = tracks.findSnapshotById(trackId).orElseThrow(() -> failure(MusicCatalogException.Reason.NOT_FOUND));
        if (row.version() != expectedVersion || row.track().coverAssetId() != null)
            throw failure(MusicCatalogException.Reason.CONFLICT);
        var sourceId = row.track().sourceId();
        if (sourceId == null || !sourceId.matches("[1-9]\\d{0,19}"))
            throw failure(MusicCatalogException.Reason.INVALID_REQUEST);
        var candidate = artwork.download(sourceId).orElseThrow(() -> failure(MusicCatalogException.Reason.NOT_FOUND));
        if (candidate.kind() != com.blogsystem.domain.music.model.MusicMediaKind.COVER)
            throw failure(MusicCatalogException.Reason.INVALID_REQUEST);
        byte[] bytes = candidate.bytes();
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        var file = new MediaUploadFile(candidate.filename(), candidate.mediaType(), bytes.length, hash);
        var request = new MediaUploadRequest(operationId, MediaUploadTargetType.MUSIC_TRACK, trackId,
                MediaUploadSlot.COVER, expectedVersion, file, null);
        var submission = uploads.create(actor, request);
        if (!submission.created() && submission.operation().progress().status() != MediaUploadStatus.READY)
            return submission.operation();
        return uploads.content(actor, operationId, new MediaUploadContent() {
            public long size() { return bytes.length; }
            public String fileName() { return file.fileName(); }
            public String mediaType() { return file.mediaType(); }
            public InputStream openStream() { return new ByteArrayInputStream(bytes); }
        });
    }

    private static MusicCatalogException failure(MusicCatalogException.Reason reason) {
        return new MusicCatalogException(reason);
    }
}
