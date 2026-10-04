package com.blogsystem.interaction.domain.repository;

import com.blogsystem.interaction.domain.query.CommentView;
import com.blogsystem.interaction.domain.query.ModerationCommentView;
import com.blogsystem.shared.domain.PageResult;

import java.util.List;

public interface InteractionQueryRepository {
    List<CommentView> listByArticle(Long articleId);

    PageResult<ModerationCommentView> adminList(Integer status, long pageNum, long pageSize);
}

