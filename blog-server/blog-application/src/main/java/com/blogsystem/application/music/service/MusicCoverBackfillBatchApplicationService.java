package com.blogsystem.application.music.service;

import com.blogsystem.domain.music.adapter.repository.MusicTrackRepository;
import com.blogsystem.domain.music.model.MusicCatalogException;
import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Operator-only migration batch. It consumes database rows, never discovers new songs. */
public final class MusicCoverBackfillBatchApplicationService {
    public record Item(String trackId, long version, String title, String status, UUID operationId, String error) {}
    private final MusicTrackRepository tracks;
    private final MusicCoverBackfillApplicationService covers;
    public MusicCoverBackfillBatchApplicationService(MusicTrackRepository tracks, MusicCoverBackfillApplicationService covers) {
        this.tracks=tracks; this.covers=covers;
    }
    public List<Item> run(long actor,int limit) {
        if(actor<=0 || limit<1 || limit>2000) throw new IllegalArgumentException("Invalid batch scope");
        List<Item> result=new ArrayList<>();
        for(var row:tracks.findAdminList().stream().limit(limit).toList()) {
            var track=row.track();
            if(track.coverAssetId()!=null) {result.add(new Item(track.id(),row.version(),track.title(),"SKIPPED_EXISTING",null,null));continue;}
            if(track.sourceId()==null || !track.sourceId().matches("[1-9]\\d{0,19}")) {result.add(new Item(track.id(),row.version(),track.title(),"MISSING_SOURCE",null,null));continue;}
            UUID id=UUID.nameUUIDFromBytes(("music-cover-backfill:"+track.id()+":"+row.version()).getBytes(StandardCharsets.UTF_8));
            try {
                var operation=covers.complete(actor,track.id(),row.version(),id);
                result.add(new Item(track.id(),row.version(),track.title(),operation.progress().status().name(),operation.intent().id(),
                        operation.outcome().safeErrorCode()==null?null:operation.outcome().safeErrorCode().name()));
            } catch(MusicCatalogException failure) {result.add(new Item(track.id(),row.version(),track.title(),"NOT_COMPLETED",id,failure.reason().name()));}
            catch(MediaUploadException failure) {result.add(new Item(track.id(),row.version(),track.title(),"NOT_COMPLETED",id,failure.reason().name()));}
        }
        return List.copyOf(result);
    }
}
