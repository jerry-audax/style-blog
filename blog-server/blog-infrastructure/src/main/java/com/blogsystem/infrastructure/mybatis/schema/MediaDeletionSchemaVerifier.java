package com.blogsystem.infrastructure.mybatis.schema;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

/** Readiness only. Never creates tables or changes resource ownership. */
public final class MediaDeletionSchemaVerifier {
    private final JdbcTemplate jdbc;
    // Approved PostgreSQL constraint definitions, not trusted names. A formatter/version change
    // requires explicit offline review, rather than silently enabling incompatible writes.
    private static final String CONSTRAINT_DIGESTS =
            "media_asset.ck_media_asset_ownership=7cae9226e9060cb8d578e19044230e7f,"
                + "media_asset.ck_media_asset_ownership_audit=92bcebc37df77a1fb44bbdd71ac76043,"
                + "media_delete_item.ck_media_delete_item_lease=e65f8f094a5cecb6a887184eb3ba60b1,"
                + "media_delete_item.ck_media_delete_item_link=fda80abae5448fd35b22b7a980b1ea56,"
                + "media_delete_item.ck_media_delete_item_ownership=0fe256d4a23613a693efc6d70de763df,"
                + "media_delete_item.ck_media_delete_item_ownership_audit=0a7a4c137405de716f57eb2360b96d6f,"
                + "media_delete_item.ck_media_delete_item_reason=f9578e5971fcacda10d603dc499bda5c,"
                + "media_delete_item.ck_media_delete_item_time=e76b6d6af59111f464227e50217b8b1d,"
                + "media_delete_operation.ck_media_delete_target_slot=d1cfa6afc196653242b5cdcfef7ee0a7,"
                + "media_delete_item.fk_media_delete_item_canonical=6b3223e791c8ce84252e3cccbdba9644,"
                + "media_delete_item.media_delete_item_asset_id_fkey=47913457c74477a85039746028080479,"
                + "media_delete_item.media_delete_item_asset_version_check=ce0cb755e175f16b60da55e30e7f8b4e,"
                + "media_delete_item.media_delete_item_attempts_check=57637e3c603a1daa1df1cf72d8847a65,"
                + "media_delete_item.media_delete_item_deletion_scope_snapshot_check=fc9eb0189d938ad70f3a5eb5d64ff819,"
                + "media_delete_item.media_delete_item_fencing_token_check=4d32e51fff2706daa989289b4fbea9d2,"
                + "media_delete_item.media_delete_item_media_kind_check=67683496726ad4f2e3e65bfaae4e4f43,"
                + "media_delete_item.media_delete_item_operation_id_fkey=a8a51defd338a115cc05df4345fa9c91,"
                + "media_delete_item.media_delete_item_pkey=4c6419b3704337bbfe50f018842a9ad3,"
                + "media_delete_item.media_delete_item_status_check=878854a089e1e981ff808ed3bfa6dd9d,"
                + "media_delete_item.media_delete_item_version_check=0c7e7088827c5a4d50d3a0e7c9f83a02,"
                + "media_delete_operation.media_delete_operation_actor_id_check=95407dad70ed49e5c59065ce32e90595,"
                + "media_delete_operation.media_delete_operation_fingerprint_check=044a4e5f3c115d64ea7beb50cc02ad37,"
                + "media_delete_operation.media_delete_operation_idempotency_key_check=f58aac32501dc3f06b15c67854394e2d,"
                + "media_delete_operation.media_delete_operation_original_version_check=5ff9bd3213eb9b3239f85c5123a72455,"
                + "media_delete_operation.media_delete_operation_pkey=4c6419b3704337bbfe50f018842a9ad3,"
                + "media_delete_operation.media_delete_operation_target_id_check=d3a7683fdaf63263dd9bfcc7f067a03d,"
                + "media_delete_operation.media_delete_operation_target_type_check=64b028bfa114d17610ba3897f3b78a0e,"
                + "media_delete_operation.uk_media_delete_actor_key=67161a5d5bae4a67df090fb3c4cd8551,"
                + "media_delete_item.uk_media_delete_item_canonical=0331743fe532fe006d2245a76a8d8fcb,"
                + "media_delete_item.uk_media_delete_operation_asset=8f2ebc1a6c40496f7dda86614b547036";
    private static final Set<String> NULLABLE =
            Set.of(
                    "media_asset.exclusive_environment",
                    "media_asset.ownership_confirmed_by",
                    "media_asset.ownership_confirmed_at",
                    "media_asset.ownership_note",
                    "media_delete_operation.media_slot",
                    "media_delete_item.exclusive_environment_snapshot",
                    "media_delete_item.ownership_confirmed_by_snapshot",
                    "media_delete_item.ownership_confirmed_at_snapshot",
                    "media_delete_item.ownership_note_snapshot",
                    "media_delete_item.next_attempt_at",
                    "media_delete_item.lease_owner",
                    "media_delete_item.lease_until",
                    "media_delete_item.linked_item_id",
                    "media_delete_item.is_canonical",
                    "media_delete_item.canonical_required");
    private static final Map<String, String> REQUIRED =
            Map.of(
                    "media_asset",
                            "deletion_scope:varchar,exclusive_environment:varchar,ownership_confirmed_by:int8,ownership_confirmed_at:timestamptz,ownership_note:varchar",
                    "media_delete_operation",
                            "id:uuid,actor_id:int8,idempotency_key:varchar,fingerprint:bpchar,target_type:varchar,target_id:varchar,media_slot:varchar,original_version:int8,committed_at:timestamptz",
                    "media_delete_item",
                            "id:uuid,operation_id:uuid,asset_id:uuid,media_kind:varchar,asset_version:int8,deletion_scope_snapshot:varchar,exclusive_environment_snapshot:varchar,ownership_confirmed_by_snapshot:int8,ownership_confirmed_at_snapshot:timestamptz,ownership_note_snapshot:varchar,status:varchar,reason:varchar,attempts:int4,next_attempt_at:timestamptz,lease_owner:varchar,lease_until:timestamptz,fencing_token:int8,version:int8,linked_item_id:uuid,is_canonical:bool,canonical_required:bool,created_at:timestamptz,updated_at:timestamptz");

    public MediaDeletionSchemaVerifier(DataSource source) {
        jdbc = new JdbcTemplate(source);
    }

    public void verify() {
        try {
            Set<String> columns = new HashSet<>();
            jdbc.query(
                    "SELECT table_name,column_name,udt_name FROM information_schema.columns WHERE"
                            + " table_schema=current_schema() AND table_name IN"
                            + " ('media_asset','media_delete_operation','media_delete_item')",
                    (org.springframework.jdbc.core.RowCallbackHandler)
                            rs ->
                                    columns.add(
                                            rs.getString(1)
                                                    + ":"
                                                    + rs.getString(2)
                                                    + ":"
                                                    + rs.getString(3)));
            for (var table : REQUIRED.entrySet())
                for (String column : table.getValue().split(","))
                    if (!columns.contains(table.getKey() + ":" + column))
                        throw new IllegalStateException();
            Map<String, String> constraints = new HashMap<>();
            jdbc.query(
                    "SELECT t.relname,c.conname,md5(pg_get_constraintdef(c.oid)),c.convalidated"
                            + " FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid WHERE"
                            + " c.connamespace=current_schema()::regnamespace",
                    (org.springframework.jdbc.core.RowCallbackHandler)
                            rs -> {
                                if (rs.getBoolean(4))
                                    constraints.put(
                                            rs.getString(1) + "." + rs.getString(2),
                                            rs.getString(3));
                            });
            for (String required : CONSTRAINT_DIGESTS.split(",")) {
                String[] parts = required.split("=");
                if (!parts[1].equals(constraints.get(parts[0]))) throw new IllegalStateException();
            }
            Map<String, Map<String, Object>> details = new HashMap<>();
            for (var row :
                    jdbc.queryForList(
                            "SELECT table_name,column_name,is_nullable,column_default,is_generated"
                                    + " FROM information_schema.columns WHERE"
                                    + " table_schema=current_schema()"))
                details.put(row.get("table_name") + "." + row.get("column_name"), row);
            for (var table : REQUIRED.entrySet())
                for (String column : table.getValue().split(",")) {
                    String key = table.getKey() + "." + column.split(":")[0];
                    if (NULLABLE.contains(key) != details.get(key).get("is_nullable").equals("YES"))
                        throw new IllegalStateException();
                }
            if (!"'UNVERIFIED'::character varying"
                    .equals(details.get("media_asset.deletion_scope").get("column_default")))
                throw new IllegalStateException();
            for (String column : List.of("is_canonical", "canonical_required"))
                if (!"ALWAYS"
                        .equals(details.get("media_delete_item." + column).get("is_generated")))
                    throw new IllegalStateException();
            var index =
                    jdbc.queryForMap(
                            "SELECT"
                                + " i.indisunique,i.indisvalid,i.indisready,i.indnkeyatts,i.indnatts,pg_get_indexdef(i.indexrelid,1,true)"
                                + " AS key,md5(pg_get_expr(i.indpred,i.indrelid)) AS predicate FROM"
                                + " pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE"
                                + " c.relnamespace=current_schema()::regnamespace AND"
                                + " c.relname='uk_media_delete_active_asset' AND"
                                + " i.indrelid=to_regclass(quote_ident(current_schema())||'.media_delete_item')");
            for (String flag : List.of("indisunique", "indisvalid", "indisready"))
                if (!Boolean.TRUE.equals(index.get(flag))) throw new IllegalStateException();
            if (((Number) index.get("indnkeyatts")).intValue() != 1
                    || ((Number) index.get("indnatts")).intValue() != 1
                    || !"asset_id".equals(index.get("key"))
                    || !"80c02ac01aa1ed11cf52f3642feecbee".equals(index.get("predicate")))
                throw new IllegalStateException();
        } catch (RuntimeException e) {
            throw new IllegalStateException("媒体删除结构缺失或不兼容，请先批准并执行单份迁移");
        }
    }

    public boolean available() {
        try {
            verify();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
