package com.blogsystem.domain.interaction.adapter.repository;

import com.blogsystem.domain.interaction.query.CommentView;
import com.blogsystem.domain.interaction.query.ModerationCommentView;
import com.blogsystem.types.model.PageResult;

import java.util.List;

public interface InteractionQueryRepository {
    List<CommentView> listByArticle(Long articleId);

    PageResult<ModerationCommentView> adminList(Integer status, long pageNum, long pageSize);
}
