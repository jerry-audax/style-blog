package com.blogsystem.infrastructure.mybatis.schema;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

/** Startup readiness only: no migrations, automatic import, or schema writes. */
public final class MediaCatalogSchemaVerifier {
    private static final Map<String, Map<String, String>> REQUIRED =
            Map.of(
                    "media_asset",
                            columns(
                                    "id:uuid,provider:varchar,object_key:varchar,media_kind:varchar,file_name:varchar,mime_type:varchar,file_size:int8,sha256:bpchar,width:int4,height:int4,duration_ms:int8,status:varchar,version:int8,created_at:timestamptz,updated_at:timestamptz"),
                    "music_track",
                            columns(
                                    "id:varchar,title:varchar,artist:varchar,source_id:varchar,audio_asset_id:uuid,cover_asset_id:uuid,lyrics_asset_id:uuid,external_cover_url:text,enabled:bool,sort_order:int4,version:int8,deleted_at:timestamptz,created_at:timestamptz,updated_at:timestamptz"),
                    "surprise_video",
                            columns(
                                    "id:varchar,title:varchar,video_asset_id:uuid,enabled:bool,sort_order:int4,version:int8,deleted_at:timestamptz,created_at:timestamptz,updated_at:timestamptz"));
    private final JdbcTemplate jdbc;

    public MediaCatalogSchemaVerifier(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
    }

    public void verify() {
        try {
            Map<String, Map<String, String>> actual = new HashMap<>();
            for (var row :
                    jdbc.queryForList(
                            """
SELECT table_name,column_name,udt_name FROM information_schema.columns
WHERE table_schema=current_schema() AND table_name IN ('media_asset','music_track','surprise_video')
""")) {
                actual.computeIfAbsent((String) row.get("table_name"), key -> new HashMap<>())
                        .put((String) row.get("column_name"), (String) row.get("udt_name"));
            }
            for (var table : REQUIRED.entrySet()) {
                var found = actual.getOrDefault(table.getKey(), Map.of());
                for (var column : table.getValue().entrySet())
                    if (!column.getValue().equals(found.get(column.getKey())))
                        throw new IllegalStateException();
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException("媒体目录数据库结构缺失或不兼容，请先执行明确迁移");
        }
    }

    private static Map<String, String> columns(String definition) {
        Map<String, String> result = new HashMap<>();
        for (String entry : definition.split(",")) {
            String[] pair = entry.split(":");
            result.put(pair[0], pair[1]);
        }
        return Map.copyOf(result);
    }
}
