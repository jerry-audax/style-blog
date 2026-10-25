package com.blogsystem.it.media;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.blogsystem.domain.asset.image.model.query.ImageMetadataQuery;
import com.blogsystem.domain.asset.metadata.model.entity.MediaAsset;
import com.blogsystem.domain.asset.metadata.model.valobj.*;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisImageMetadataRepository;
import com.blogsystem.infrastructure.adapter.repository.asset.MybatisMediaAssetRepository;
import com.blogsystem.infrastructure.mybatis.converter.asset.MediaAssetConverter;
import com.blogsystem.infrastructure.mybatis.mapper.asset.ImageMetadataMapper;
import com.blogsystem.infrastructure.mybatis.mapper.asset.MediaAssetMapper;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MediaImageMetadataRepositoryIntegrationTest {
    @Test void databasePageIncludesSecondPageAndKeepsLiteralSearchNullZeroAndScope() throws Exception {
        try (var schema = MediaPostgresTestSupport.createSchema()) {
            schema.executeDdl();
            try (var session = session(schema)) {
                var converter = new MediaAssetConverter();
                var writer = new MybatisMediaAssetRepository(session.getMapper(MediaAssetMapper.class), converter);
                var reader = new MybatisImageMetadataRepository(session.getMapper(ImageMetadataMapper.class), converter);
                Instant time = Instant.parse("2026-10-18T00:00:00.123456Z");
                for (int n = 1; n <= 53; n++) {
                    String name = n == 1 ? "中文+100%.png" : n == 2 ? "中文_封面.png" : "image-" + n + ".png";
                    writer.insert(asset(n, "cloudflare_imgbed", "blog/images/" + name, name, MediaKind.IMAGE, MediaAssetStatus.ACTIVE,
                            n == 1 ? null : 0L, time));
                }
                writer.insert(asset(60, "other", "blog/images/other.png", "other.png", MediaKind.IMAGE, MediaAssetStatus.ACTIVE, 1L, time));
                writer.insert(asset(61, "cloudflare_imgbed", "blog-other/a.png", "a.png", MediaKind.IMAGE, MediaAssetStatus.ACTIVE, 1L, time));
                writer.insert(asset(62, "cloudflare_imgbed", "blog/images/deleted.png", "deleted.png", MediaKind.IMAGE, MediaAssetStatus.ACTIVE, 1L, time));
                writer.insert(asset(63, "cloudflare_imgbed", "blog/audio/a.mp3", "a.mp3", MediaKind.AUDIO, MediaAssetStatus.ACTIVE, 1L, time));
                writer.insert(asset(64, "cloudflare_imgbed", "blog", "root.png", MediaKind.IMAGE, MediaAssetStatus.ACTIVE, 1L, time));
                session.commit();
                schema.execute("UPDATE media_asset SET status='DELETED' WHERE id='" + id(62) + "'");
                var first = reader.findPage(query("", 0, 50));
                var second = reader.findPage(query("", 50, 50));
                assertEquals(53, first.total()); assertEquals(50, first.records().size());
                assertEquals(List.of(id(51), id(52), id(53)), second.records().stream().map(MediaAsset::id).toList());
                assertEquals(53, second.total()); assertNull(first.records().get(0).metadata().fileSize());
                assertNull(first.records().get(0).metadata().mimeType());
                assertEquals(0L, first.records().get(1).metadata().fileSize());
                assertEquals(List.of(id(1)), reader.findPage(query("%", 0, 20)).records().stream().map(MediaAsset::id).toList());
                assertEquals(List.of(id(2)), reader.findPage(query("_", 0, 20)).records().stream().map(MediaAsset::id).toList());
                assertEquals(2, reader.findPage(query("中文", 0, 20)).total());
                assertTrue(reader.findPage(query("", 100, 20)).records().isEmpty());
                writer.insert(asset(65, "cloudflare_imgbed", "blog/images/new.png", "new.png", MediaKind.IMAGE, MediaAssetStatus.ACTIVE, 1L, time.plusSeconds(1)));
                session.commit();
                assertEquals(id(65), reader.findPage(query("", 0, 1)).records().get(0).id());
                session.commit();
                schema.execute("ALTER TABLE media_asset RENAME TO unavailable_assets");
                assertThrows(MediaAssetReadException.class, () -> reader.findPage(query("", 0, 20)));
            }
        }
    }

    @Test void queryRejectsInvalidOffsetsLimitsAndControlCharacters() {
        for (int offset : new int[]{-1, 100001}) assertThrows(IllegalArgumentException.class, () -> query("", offset, 20));
        for (int count : new int[]{0, 51}) assertThrows(IllegalArgumentException.class, () -> query("", 0, count));
        for (String search : new String[]{null, "x".repeat(101), "a\n"})
            assertThrows(IllegalArgumentException.class, () -> query(search, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> new ImageMetadataQuery(" ", new MediaObjectKey("blog"), "", 0, 20));
    }

    static SqlSession session(MediaPostgresTestSupport.ScopedSchema schema) throws Exception {
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(MediaAssetMapper.class); config.addMapper(ImageMetadataMapper.class);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(MediaImageLedgerDdlIntegrationTest.dataSource(schema));
        factory.setConfiguration(config); return factory.getObject().openSession(false);
    }

    static ImageMetadataQuery query(String search, int offset, int count) {
        return new ImageMetadataQuery("cloudflare_imgbed", new MediaObjectKey("blog"), search, offset, count);
    }

    static UUID id(int n) { return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", n)); }

    private static MediaAsset asset(int n, String provider, String key, String name, MediaKind kind,
            MediaAssetStatus status, Long bytes, Instant time) {
        return MediaAsset.restore(id(n), provider, new MediaObjectKey(key), kind,
                new MediaMetadata(name, bytes == null ? null : "image/png", bytes, null, null, null, null), status, 0, time, time);
    }
}
