package com.blogsystem.infrastructure.mybatis.mapper.asset;

import com.blogsystem.domain.asset.upload.model.valobj.MediaUploadExecutionFence;
import com.blogsystem.infrastructure.mybatis.record.asset.MediaUploadOperationRecord;
import com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.OffsetDateTimeTypeHandler;

import java.util.List;
import java.util.UUID;

/** Narrow SQL commands only. Caller owns transaction and row-lock lifetime. */
@Mapper
public interface MediaUploadOperationMapper {
    String COLUMNS =
            "id, actor_id, request_fingerprint, operation_type, source_operation_id, target_type,"
                + " target_id, slot, expected_target_version, expected_old_asset_id, file_name,"
                + " mime_type, file_size, source_sha256, candidate_asset_id, target_payload::text"
                + " AS target_payload, provider, operation_folder, confirmed_object_key,"
                + " transport_mode, provider_upload_id, chunk_size, total_chunks, confirmed_chunks,"
                + " in_flight_chunk, status, stage, safe_error_code, version, lease_owner,"
                + " lease_until, fencing_token, pause_requested, execution_attempts,"
                + " recovery_attempts, finalize_attempts, result_snapshot::text AS result_snapshot,"
                + " resolved_by_operation_id, created_at, updated_at";
    String OWNED =
            " WHERE actor_id=#{actorId} AND"
                + " id=#{id,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER}";
    String FENCED =
            " WHERE"
                + " id=#{fence.operationId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER}"
                + " AND actor_id=#{fence.actorId} AND"
                + " lease_owner=#{fence.workerId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER}"
                + " AND fencing_token=#{fence.fencingToken} AND lease_until>clock_timestamp()";
    String GUARDED = FENCED + " AND version=#{version} AND version<9223372036854775807";
    String BUMP = ",version=version+1,updated_at=GREATEST(updated_at,created_at,clock_timestamp())";

    @Results(
            id = "mediaUploadOperation",
            value = {
                @Result(
                        column = "id",
                        property = "id",
                        id = true,
                        jdbcType = JdbcType.OTHER,
                        typeHandler = UuidTypeHandler.class),
                @Result(column = "actor_id", property = "actorId"),
                @Result(column = "request_fingerprint", property = "requestFingerprint"),
                @Result(column = "operation_type", property = "operationType"),
                @Result(
                        column = "source_operation_id",
                        property = "sourceOperationId",
                        jdbcType = JdbcType.OTHER,
                        typeHandler = UuidTypeHandler.class),
                @Result(column = "target_type", property = "targetType"),
                @Result(column = "target_id", property = "targetId"),
                @Result(column = "slot", property = "slot"),
                @Result(column = "expected_target_version", property = "expectedTargetVersion"),
                @Result(
                        column = "expected_old_asset_id",
                        property = "expectedOldAssetId",
                        jdbcType = JdbcType.OTHER,
                        typeHandler = UuidTypeHandler.class),
                @Result(column = "file_name", property = "fileName"),
                @Result(column = "mime_type", property = "mimeType"),
                @Result(column = "file_size", property = "fileSize"),
                @Result(column = "source_sha256", property = "sourceSha256"),
                @Result(
                        column = "candidate_asset_id",
                        property = "candidateAssetId",
                        jdbcType = JdbcType.OTHER,
                        typeHandler = UuidTypeHandler.class),
                @Result(column = "target_payload", property = "targetPayload"),
                @Result(column = "provider", property = "provider"),
                @Result(column = "operation_folder", property = "operationFolder"),
                @Result(column = "confirmed_object_key", property = "confirmedObjectKey"),
                @Result(column = "transport_mode", property = "transportMode"),
                @Result(column = "provider_upload_id", property = "providerUploadId"),
                @Result(column = "chunk_size", property = "chunkSize"),
                @Result(column = "total_chunks", property = "totalChunks"),
                @Result(column = "confirmed_chunks", property = "confirmedChunks"),
                @Result(column = "in_flight_chunk", property = "inFlightChunk"),
                @Result(column = "status", property = "status"),
                @Result(column = "stage", property = "stage"),
                @Result(column = "safe_error_code", property = "safeErrorCode"),
                @Result(column = "version", property = "version"),
                @Result(
                        column = "lease_owner",
                        property = "leaseOwner",
                        jdbcType = JdbcType.OTHER,
                        typeHandler = UuidTypeHandler.class),
                @Result(
                        column = "lease_until",
                        property = "leaseUntil",
                        typeHandler = OffsetDateTimeTypeHandler.class),
                @Result(column = "fencing_token", property = "fencingToken"),
                @Result(column = "pause_requested", property = "pauseRequested"),
                @Result(column = "execution_attempts", property = "executionAttempts"),
                @Result(column = "recovery_attempts", property = "recoveryAttempts"),
                @Result(column = "finalize_attempts", property = "finalizeAttempts"),
                @Result(column = "result_snapshot", property = "resultSnapshot"),
                @Result(
                        column = "resolved_by_operation_id",
                        property = "resolvedByOperationId",
                        jdbcType = JdbcType.OTHER,
                        typeHandler = UuidTypeHandler.class),
                @Result(
                        column = "created_at",
                        property = "createdAt",
                        typeHandler = OffsetDateTimeTypeHandler.class),
                @Result(
                        column = "updated_at",
                        property = "updatedAt",
                        typeHandler = OffsetDateTimeTypeHandler.class)
            })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT " + COLUMNS + " FROM media_upload_operation" + OWNED)
    MediaUploadOperationRecord selectOwned(@Param("actorId") long actorId, @Param("id") UUID id);

    @ResultMap("mediaUploadOperation")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT " + COLUMNS + " FROM media_upload_operation WHERE actor_id=#{actorId}"
            + " AND target_type='MUSIC_TRACK' AND target_id=#{trackId} AND slot='COVER'"
            + " AND status IN ('READY','UPLOADING','PAUSED','UPLOADED','UNKNOWN','CONFLICT')"
            + " AND resolved_by_operation_id IS NULL ORDER BY created_at,id LIMIT 1")
    MediaUploadOperationRecord unresolvedMusicCover(@Param("actorId") long actorId, @Param("trackId") String trackId);

    @ResultMap("mediaUploadOperation")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT " + COLUMNS + " FROM media_upload_operation" + OWNED + " FOR UPDATE")
    MediaUploadOperationRecord lockOwned(@Param("actorId") long actorId, @Param("id") UUID id);

    @ResultMap("mediaUploadOperation")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT " + COLUMNS + " FROM media_upload_operation" + FENCED + " FOR UPDATE")
    MediaUploadOperationRecord lockFenced(@Param("fence") MediaUploadExecutionFence fence);

    @ResultMap("mediaUploadOperation")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM media_upload_operation WHERE actor_id=#{actorId} ORDER BY created_at"
                    + " DESC,id ASC LIMIT #{limit} OFFSET #{offset}")
    List<MediaUploadOperationRecord> history(
            @Param("actorId") long actorId, @Param("offset") int offset, @Param("limit") int limit);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT count(*) FROM media_upload_operation WHERE actor_id=#{actorId}")
    long countOwned(@Param("actorId") long actorId);

    @Insert(
            "INSERT INTO media_upload_operation (id, actor_id, request_fingerprint, operation_type,"
                + " source_operation_id, target_type, target_id, slot, expected_target_version,"
                + " expected_old_asset_id, file_name, mime_type, file_size, source_sha256,"
                + " candidate_asset_id, target_payload, provider, operation_folder,"
                + " confirmed_object_key, transport_mode, provider_upload_id, chunk_size,"
                + " total_chunks, confirmed_chunks, in_flight_chunk, status, stage,"
                + " safe_error_code, version, lease_owner, lease_until, fencing_token,"
                + " pause_requested, execution_attempts, recovery_attempts, finalize_attempts,"
                + " result_snapshot, resolved_by_operation_id, created_at, updated_at) VALUES"
                + " (#{id,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER},"
                + " #{actorId}, #{requestFingerprint}, #{operationType},"
                + " #{sourceOperationId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER},"
                + " #{targetType}, #{targetId}, #{slot}, #{expectedTargetVersion},"
                + " #{expectedOldAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER},"
                + " #{fileName}, #{mimeType}, #{fileSize}, #{sourceSha256},"
                + " #{candidateAssetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER},"
                + " CAST(#{targetPayload} AS jsonb), #{provider}, #{operationFolder},"
                + " #{confirmedObjectKey}, #{transportMode}, #{providerUploadId}, #{chunkSize},"
                + " #{totalChunks}, #{confirmedChunks}, #{inFlightChunk}, #{status}, #{stage},"
                + " #{safeErrorCode}, #{version},"
                + " #{leaseOwner,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER},"
                + " #{leaseUntil}, #{fencingToken}, #{pauseRequested}, #{executionAttempts},"
                + " #{recoveryAttempts}, #{finalizeAttempts}, CAST(#{resultSnapshot} AS jsonb),"
                + " #{resolvedByOperationId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER},"
                + " #{createdAt}, #{updatedAt})")
    int insert(MediaUploadOperationRecord record);

    @Update(
            """
UPDATE media_upload_operation SET status='UPLOADING',stage='VERIFY',
    lease_owner=#{workerId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER},lease_until=clock_timestamp()+interval '180 seconds',
    fencing_token=fencing_token+1,execution_attempts=execution_attempts+1,pause_requested=FALSE,safe_error_code=NULL,
    version=version+1,updated_at=GREATEST(updated_at,created_at,clock_timestamp())
WHERE actor_id=#{actorId} AND id=#{id,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER} AND status IN ('READY','PAUSED')
    AND in_flight_chunk IS NULL AND (lease_until IS NULL OR lease_until<=clock_timestamp())
    AND version<9223372036854775807 AND fencing_token<9223372036854775807 AND execution_attempts<2147483647
""")
    int claimContent(
            @Param("actorId") long actorId, @Param("id") UUID id, @Param("workerId") UUID workerId);

    @Update(
            """
UPDATE media_upload_operation SET
    status=CASE WHEN status='UPLOADING' AND stage='VERIFY' THEN CASE WHEN provider_upload_id IS NULL THEN 'READY' ELSE 'PAUSED' END
                WHEN status='UPLOADING' THEN 'UNKNOWN' ELSE status END,
    stage=CASE WHEN status='UPLOADING' AND stage='VERIFY' THEN CASE WHEN provider_upload_id IS NULL THEN 'NONE' ELSE 'CHUNK' END ELSE stage END,
    lease_owner=CASE WHEN status='UPLOADING' AND stage='VERIFY' THEN NULL ELSE #{workerId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER} END,
    lease_until=CASE WHEN status='UPLOADING' AND stage='VERIFY' THEN NULL ELSE clock_timestamp()+interval '180 seconds' END,
    pause_requested=CASE WHEN status='UPLOADING' AND stage='VERIFY' THEN FALSE ELSE pause_requested END,
    safe_error_code=CASE WHEN status='UPLOADING' AND stage<>'VERIFY' THEN 'LEASE_LOST' ELSE safe_error_code END,
    fencing_token=fencing_token+1,recovery_attempts=recovery_attempts+1,
    version=version+1,updated_at=GREATEST(updated_at,created_at,clock_timestamp())
WHERE actor_id=#{actorId} AND id=#{id,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER} AND version=#{version}
    AND (status IN ('UNKNOWN','UPLOADED') OR (status='UPLOADING' AND lease_until<=clock_timestamp()))
    AND (lease_until IS NULL OR lease_until<=clock_timestamp())
    AND version<9223372036854775807 AND fencing_token<9223372036854775807 AND recovery_attempts<2147483647
""")
    int claimRecovery(
            @Param("actorId") long actorId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("workerId") UUID workerId);

    @Update(
            "UPDATE media_upload_operation SET lease_until=clock_timestamp()+interval '180 seconds'"
                    + BUMP
                    + FENCED
                    + " AND status IN ('UPLOADING','UPLOADED','UNKNOWN') AND"
                    + " version<9223372036854775807")
    int renew(@Param("fence") MediaUploadExecutionFence fence);

    @Update(
            "UPDATE media_upload_operation SET pause_requested=TRUE"
                    + BUMP
                    + OWNED
                    + " AND version=#{version} AND version<9223372036854775807 AND"
                    + " status='UPLOADING' AND transport_mode='CHUNKED' AND stage IN"
                    + " ('INIT','CHUNK') AND lease_until>clock_timestamp()")
    int requestPause(
            @Param("actorId") long actorId, @Param("id") UUID id, @Param("version") long version);

    @Update(
            "UPDATE media_upload_operation SET stage=#{stage},in_flight_chunk=#{index}"
                    + BUMP
                    + GUARDED)
    int begin(
            @Param("fence") MediaUploadExecutionFence fence,
            @Param("version") long version,
            @Param("stage") String stage,
            @Param("index") Integer index);

    @Update(
            "UPDATE media_upload_operation SET stage='CHUNK',provider_upload_id=#{uploadId}"
                    + BUMP
                    + GUARDED)
    int initialized(
            @Param("fence") MediaUploadExecutionFence fence,
            @Param("version") long version,
            @Param("uploadId") String uploadId);

    @Update(
            "UPDATE media_upload_operation SET"
                    + " confirmed_chunks=confirmed_chunks+1,in_flight_chunk=NULL"
                    + BUMP
                    + GUARDED)
    int chunkConfirmed(
            @Param("fence") MediaUploadExecutionFence fence, @Param("version") long version);

    @Update(
            "UPDATE media_upload_operation SET"
                + " status='UPLOADED',stage='PERSIST',confirmed_object_key=#{key},confirmed_chunks=total_chunks,"
                + "in_flight_chunk=NULL,pause_requested=FALSE,safe_error_code=NULL"
                    + BUMP
                    + GUARDED)
    int receipt(
            @Param("fence") MediaUploadExecutionFence fence,
            @Param("version") long version,
            @Param("key") String key);

    @Update(
            "UPDATE media_upload_operation SET"
                + " status='PAUSED',stage='CHUNK',lease_owner=NULL,lease_until=NULL,safe_error_code=NULL"
                    + BUMP
                    + GUARDED)
    int pause(@Param("fence") MediaUploadExecutionFence fence, @Param("version") long version);

    @Update(
            "UPDATE media_upload_operation SET"
                    + " status='UNKNOWN',lease_owner=NULL,lease_until=NULL,safe_error_code=#{code}"
                    + BUMP
                    + GUARDED)
    int unknown(
            @Param("fence") MediaUploadExecutionFence fence,
            @Param("version") long version,
            @Param("code") String code);

    @Update(
            "UPDATE media_upload_operation SET"
                + " status='FAILED',lease_owner=NULL,lease_until=NULL,in_flight_chunk=NULL,pause_requested=FALSE,safe_error_code=#{code}"
                    + BUMP
                    + GUARDED)
    int fail(
            @Param("fence") MediaUploadExecutionFence fence,
            @Param("version") long version,
            @Param("code") String code);

    @Update(
            "UPDATE media_upload_operation SET"
                + " status='CONFLICT',lease_owner=NULL,lease_until=NULL,pause_requested=FALSE,safe_error_code=#{code}"
                    + BUMP
                    + GUARDED)
    int conflict(
            @Param("fence") MediaUploadExecutionFence fence,
            @Param("version") long version,
            @Param("code") String code);

    @Update(
            "UPDATE media_upload_operation SET finalize_attempts=finalize_attempts+1"
                    + BUMP
                    + GUARDED
                    + " AND finalize_attempts<2147483647")
    int finalizeStarted(
            @Param("fence") MediaUploadExecutionFence fence, @Param("version") long version);

    @Update(
            "UPDATE media_upload_operation SET status='SUCCEEDED',result_snapshot=CAST(#{snapshot}"
                    + " AS jsonb),lease_owner=NULL,lease_until=NULL,"
                    + "in_flight_chunk=NULL,pause_requested=FALSE,safe_error_code=NULL"
                    + BUMP
                    + GUARDED
                    + " AND status='UPLOADED'")
    int complete(
            @Param("fence") MediaUploadExecutionFence fence,
            @Param("version") long version,
            @Param("snapshot") String snapshot);

    @Update(
            """
UPDATE media_upload_operation s SET resolved_by_operation_id=#{childId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER},
    version=s.version+1,updated_at=GREATEST(s.updated_at,s.created_at,clock_timestamp())
WHERE s.id=#{sourceId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER} AND s.actor_id=#{fence.actorId} AND s.version=#{version}
    AND s.version<9223372036854775807 AND s.status='CONFLICT' AND s.operation_type='UPLOAD_AND_BIND'
    AND s.target_type='MUSIC_TRACK' AND s.resolved_by_operation_id IS NULL
    AND EXISTS (SELECT 1 FROM media_upload_operation c WHERE c.id=#{childId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER}
        AND c.id=#{fence.operationId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER} AND c.actor_id=s.actor_id
        AND c.source_operation_id=s.id AND c.operation_type='BIND_UPLOADED'
        AND c.target_type=s.target_type AND c.target_id=s.target_id AND c.slot=s.slot
        AND c.candidate_asset_id=s.candidate_asset_id AND c.provider=s.provider
        AND c.operation_folder=s.operation_folder AND c.confirmed_object_key=s.confirmed_object_key
        AND c.file_size=s.file_size AND c.source_sha256=s.source_sha256
        AND c.status='UPLOADED' AND c.lease_owner=#{fence.workerId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler,jdbcType=OTHER}
        AND c.fencing_token=#{fence.fencingToken} AND c.lease_until>clock_timestamp())
""")
    int resolve(
            @Param("fence") MediaUploadExecutionFence fence,
            @Param("sourceId") UUID sourceId,
            @Param("childId") UUID childId,
            @Param("version") long version);
}
