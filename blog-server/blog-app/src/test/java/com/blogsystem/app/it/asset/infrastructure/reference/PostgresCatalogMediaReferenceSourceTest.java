package com.blogsystem.app.it.asset.infrastructure.reference;

import com.blogsystem.application.asset.inventory.service.MusicMediaReferenceSource;
import com.blogsystem.application.asset.inventory.service.SurpriseMediaReferenceSource;

import com.blogsystem.domain.asset.inventory.model.query.MediaReference;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaKind;
import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PostgresCatalogMediaReferenceSourceTest {
    @Test void failedDatabaseReferenceReadIsNotAnEmptyInventory(){
        assertThrows(com.blogsystem.domain.asset.inventory.model.valobj.MediaInventoryException.class,
                ()->new MusicMediaReferenceSource(()->{throw new IllegalStateException("SELECT hidden SQL");}).list());
        assertThrows(com.blogsystem.domain.asset.inventory.model.valobj.MediaInventoryException.class,
                ()->new SurpriseMediaReferenceSource(()->{throw new IllegalStateException("SELECT hidden SQL");}).list());
    }
    @Test void referencesUseApplicationQueriesWithoutLegacyCatalogOrProvider(){
        var audio=new MediaReference(MediaKind.AUDIO,new MediaObjectKey("blog/music/a.mp3"),"music_track","disabled","禁用歌曲");
        var video=new MediaReference(MediaKind.VIDEO,new MediaObjectKey("blog/surprise/a.mp4"),"surprise_video","disabled-video","禁用视频");
        assertEquals(List.of(audio),new MusicMediaReferenceSource(()->List.of(audio)).list());
        assertEquals(List.of(video),new SurpriseMediaReferenceSource(()->List.of(video)).list());
    }
}
