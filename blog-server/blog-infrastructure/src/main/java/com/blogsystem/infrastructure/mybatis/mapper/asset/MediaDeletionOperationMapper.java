package com.blogsystem.infrastructure.mybatis.mapper.asset;

import com.blogsystem.infrastructure.mybatis.record.asset.MediaDeleteItemRecord;
import com.blogsystem.infrastructure.mybatis.record.asset.MediaDeleteOperationRecord;
import com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface MediaDeletionOperationMapper {
    String UUID_ID =
            "#{id,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler}";

    @Insert(
            """
INSERT INTO media_delete_operation(id,actor_id,idempotency_key,fingerprint,target_type,target_id,media_slot,original_version,committed_at)
VALUES (#{id,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler},#{actorId},#{idempotencyKey},#{fingerprint},#{targetType},#{targetId},#{mediaSlot},#{originalVersion},#{committedAt})
""")
    int insertOperation(MediaDeleteOperationRecord row);

    @Insert(
            """
INSERT INTO media_delete_item(id,operation_id,asset_id,media_kind,asset_version,deletion_scope_snapshot,exclusive_environment_snapshot,
  ownership_confirmed_by_snapshot,ownership_confirmed_at_snapshot,ownership_note_snapshot,status,reason,linked_item_id,created_at,updated_at)
VALUES (#{id,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler},
  #{operationId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler},
  #{assetId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler},#{mediaKind},#{assetVersion},#{deletionScopeSnapshot},#{exclusiveEnvironmentSnapshot},
  #{ownershipConfirmedBySnapshot},#{ownershipConfirmedAtSnapshot},#{ownershipNoteSnapshot},#{status},#{reason},
  #{linkedItemId,typeHandler=com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler},#{createdAt},#{updatedAt})
""")
    int insertItem(MediaDeleteItemRecord row);

    @Results(
            id = "deletionOperation",
            value = @Result(column = "id", property = "id", typeHandler = UuidTypeHandler.class))
    @Select("SELECT * FROM media_delete_operation WHERE id=" + UUID_ID + " AND actor_id=#{actorId}")
    MediaDeleteOperationRecord selectById(@Param("id") UUID id, @Param("actorId") long actorId);

    @ResultMap("deletionOperation")
    @Select(
            "SELECT * FROM media_delete_operation WHERE actor_id=#{actorId} AND"
                    + " idempotency_key=#{key}")
    MediaDeleteOperationRecord selectByActorAndKey(
            @Param("actorId") long actorId, @Param("key") String key);

    @ResultMap("deletionOperation")
    @Select(
            "SELECT * FROM media_delete_operation WHERE actor_id=#{actorId} ORDER BY committed_at"
                    + " DESC,id ASC LIMIT #{limit} OFFSET #{offset}")
    List<MediaDeleteOperationRecord> selectPage(
            @Param("actorId") long actorId, @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT count(*) FROM media_delete_operation WHERE actor_id=#{actorId}")
    long count(long actorId);

    @Results(
            id = "deletionItem",
            value = {
                @Result(column = "id", property = "id", typeHandler = UuidTypeHandler.class),
                @Result(
                        column = "operation_id",
                        property = "operationId",
                        typeHandler = UuidTypeHandler.class),
                @Result(
                        column = "asset_id",
                        property = "assetId",
                        typeHandler = UuidTypeHandler.class),
                @Result(
                        column = "linked_item_id",
                        property = "linkedItemId",
                        typeHandler = UuidTypeHandler.class)
            })
    @Select("SELECT * FROM media_delete_item WHERE operation_id=" + UUID_ID + " ORDER BY asset_id")
    List<MediaDeleteItemRecord> selectItems(@Param("id") UUID id);

    @ResultMap("deletionItem")
    @Select("SELECT * FROM media_delete_item WHERE id=" + UUID_ID)
    MediaDeleteItemRecord selectItem(@Param("id") UUID id);
}
