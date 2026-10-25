package com.blogsystem.infrastructure.mybatis.schema;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

/** Read-only, fail-closed readiness for the approved incremental upload DDL. Never runs a migration. */
public final class MediaUploadSchemaVerifier {
    private static final String TABLE = "media_upload_operation";
    private static final String RELATION =
            "to_regclass(quote_ident(current_schema())||'.media_upload_operation')";
    private static final Set<String> NULLABLE =
            Set.of(
                    "source_operation_id",
                    "expected_target_version",
                    "expected_old_asset_id",
                    "confirmed_object_key",
                    "provider_upload_id",
                    "in_flight_chunk",
                    "safe_error_code",
                    "lease_owner",
                    "lease_until",
                    "result_snapshot",
                    "resolved_by_operation_id");

    private record Column(String type, Integer length) {}

    private static final Map<String, Column> COLUMNS = columns();
    // Fingerprints of pg_get_constraintdef from the approved DDL, not name/substring checks.
    // A PostgreSQL formatter/version difference refuses enablement and requires an offline schema
    // review.
    private static final Map<String, String> CONSTRAINTS =
            Map.ofEntries(
                    Map.entry("ck_media_upload_actor", "95407dad70ed49e5c59065ce32e90595"),
                    Map.entry("ck_media_upload_checkpoint", "f74232e7ecf176fd41373ec5c2dd24d6"),
                    Map.entry("ck_media_upload_counters", "53f93d1fc6678954855eb4062401246c"),
                    Map.entry("ck_media_upload_enums", "013812dbcd6cbbee535a05bc5488d8e4"),
                    Map.entry("ck_media_upload_file_images", "cc93289d2434922e5d6749a89fdad062"),
                    Map.entry("ck_media_upload_hashes", "15f028812b290ff3811cd0fc750dac6c"),
                    Map.entry("ck_media_upload_lease", "e65f8f094a5cecb6a887184eb3ba60b1"),
                    Map.entry("ck_media_upload_locations", "d86acd4ef3b5dcd3d6cc1c6a2a642566"),
                    Map.entry("ck_media_upload_mode_stage", "38af27b64e52c44fa1c34f1ceedf9ca4"),
                    Map.entry("ck_media_upload_operation_type", "e076fa04d64b2fdce39733e390e1d4aa"),
                    Map.entry("ck_media_upload_provider", "4882da88c2d12c8f3a0b0550b1549b76"),
                    Map.entry("ck_media_upload_resolved", "d5b29d970c9265ee2b935fee62623b65"),
                    Map.entry("ck_media_upload_result_images", "3d340cd153780fe0a2106fad8d911575"),
                    Map.entry("ck_media_upload_source", "693bf4949c15504f2c7aae8556cc258f"),
                    Map.entry("ck_media_upload_stage", "fcaf8da3bb8a32be022310db13e6f6ef"),
                    Map.entry("ck_media_upload_target_images", "1729c6ec7e4ae58a700d95331365f6a2"),
                    Map.entry("ck_media_upload_timestamps", "e76b6d6af59111f464227e50217b8b1d"),
                    Map.entry("ck_media_upload_transport", "d1c0429d33c4e37c54f8cc9a53f31f79"),
                    Map.entry("fk_media_upload_resolved", "a2ddf9a467690300f071a344a3ac62e7"),
                    Map.entry("fk_media_upload_source", "aff3554831221c33f2f15f4b86308449"),
                    Map.entry("media_upload_operation_pkey", "4c6419b3704337bbfe50f018842a9ad3"));

    private record Index(boolean unique, String columns, String order, String predicate) {}

    private static final String EMPTY = "d41d8cd98f00b204e9800998ecf8427e";
    private static final Map<String, Index> INDEXES =
            Map.of(
                    "media_upload_operation_pkey", new Index(true, "id", "0", EMPTY),
                    "uk_media_upload_original_folder",
                            new Index(
                                    true,
                                    "provider,operation_folder",
                                    "0 0",
                                    "8e1c66785a73c77eab1eadadb767fa12"),
                    "ix_media_upload_actor_created",
                            new Index(false, "actor_id,created_at,id", "0 3 0", EMPTY),
                    "ix_media_upload_status_lease",
                            new Index(false, "status,lease_until", "0 0", EMPTY));
    private final JdbcTemplate jdbc;

    public MediaUploadSchemaVerifier(DataSource source) {
        jdbc = new JdbcTemplate(source);
    }

    public void verify() {
        try {
            var found = new HashMap<String, Column>();
            for (var row :
                    jdbc.queryForList(
                            "SELECT column_name,udt_name,is_nullable,character_maximum_length FROM"
                                + " information_schema.columns WHERE table_schema=current_schema()"
                                + " AND table_name=?",
                            TABLE)) {
                String name = (String) row.get("column_name");
                if (NULLABLE.contains(name) != row.get("is_nullable").equals("YES"))
                    throw new IllegalStateException();
                Number size = (Number) row.get("character_maximum_length");
                found.put(
                        name,
                        new Column(
                                (String) row.get("udt_name"),
                                size == null ? null : size.intValue()));
            }
            if (!COLUMNS.equals(found)) throw new IllegalStateException();
            var constraints = new HashMap<String, String>();
            for (var row :
                    jdbc.queryForList(
                            "SELECT conname,convalidated,md5(pg_get_constraintdef(oid)) AS digest"
                                    + " FROM pg_constraint WHERE conrelid="
                                    + RELATION
                                    + " AND contype IN ('p','f','c')")) {
                if (!Boolean.TRUE.equals(row.get("convalidated")))
                    throw new IllegalStateException();
                constraints.put((String) row.get("conname"), (String) row.get("digest"));
            }
            if (!CONSTRAINTS.equals(constraints)) throw new IllegalStateException();
            var indexes = new HashMap<String, Index>();
            for (var row :
                    jdbc.queryForList(
                            "SELECT"
                                + " c.relname,i.indisvalid,i.indisready,i.indisunique,i.indoption::text"
                                + " AS ordering,md5(coalesce(pg_get_expr(i.indpred,i.indrelid),''))"
                                + " AS predicate,(SELECT string_agg(a.attname,',' ORDER BY k.ord)"
                                + " FROM unnest(i.indkey) WITH ORDINALITY k(attnum,ord) JOIN"
                                + " pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=k.attnum)"
                                + " AS columns FROM pg_index i JOIN pg_class c ON"
                                + " c.oid=i.indexrelid WHERE i.indrelid="
                                    + RELATION)) {
                if (!Boolean.TRUE.equals(row.get("indisvalid"))
                        || !Boolean.TRUE.equals(row.get("indisready")))
                    throw new IllegalStateException();
                indexes.put(
                        (String) row.get("relname"),
                        new Index(
                                Boolean.TRUE.equals(row.get("indisunique")),
                                (String) row.get("columns"),
                                (String) row.get("ordering"),
                                (String) row.get("predicate")));
            }
            for (var required : INDEXES.entrySet())
                if (!required.getValue().equals(indexes.get(required.getKey())))
                    throw new IllegalStateException();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("上传台账数据库结构缺失或不兼容，请先核对明确迁移");
        }
    }

    private static Map<String, Column> columns() {
        String definition =
                "id:uuid,actor_id:int8,request_fingerprint:bpchar:64,operation_type:varchar:24,source_operation_id:uuid,target_type:varchar:24,target_id:varchar:64,slot:varchar:16,expected_target_version:int8,expected_old_asset_id:uuid,file_name:varchar:255,mime_type:varchar:128,file_size:int8,source_sha256:bpchar:64,candidate_asset_id:uuid,target_payload:jsonb,provider:varchar:32,operation_folder:varchar:512,confirmed_object_key:varchar:512,transport_mode:varchar:16,provider_upload_id:varchar:128,chunk_size:int4,total_chunks:int4,confirmed_chunks:int4,in_flight_chunk:int4,status:varchar:24,stage:varchar:16,safe_error_code:varchar:40,version:int8,lease_owner:uuid,lease_until:timestamptz,fencing_token:int8,pause_requested:bool,execution_attempts:int4,recovery_attempts:int4,finalize_attempts:int4,result_snapshot:jsonb,resolved_by_operation_id:uuid,created_at:timestamptz,updated_at:timestamptz";
        Map<String, Column> columns = new HashMap<>();
        for (String entry : definition.split(",")) {
            String[] parts = entry.split(":");
            columns.put(
                    parts[0],
                    new Column(parts[1], parts.length == 3 ? Integer.valueOf(parts[2]) : null));
        }
        return Map.copyOf(columns);
    }
}
