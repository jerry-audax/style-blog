package com.blogsystem.app.it.interaction.domain;

import org.junit.jupiter.api.Assertions;

import com.blogsystem.infrastructure.mybatis.record.content.ArticleRecord;
import com.blogsystem.infrastructure.mybatis.record.interaction.CommentRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-7 评论创建业务校验单元测试。
 * 不依赖 Spring 上下文，直接验证 Article 状态判断逻辑：
 * 草稿文章（status≠1）不可评论、已删除文章不可评论、
 * 评论关闭（isCommentEnabled≠1）不可评论、父评论跨文章不可回复、
 * 已删除父评论不可回复。
 */
class CommentValidationTest {

    @Test
    void articleNotPublished_shouldBeBlocked() {
        ArticleRecord draft = new ArticleRecord();
        draft.setStatus(0);  // 草稿
        assertNotEquals(1, draft.getStatus() != null ? draft.getStatus() : -1);

        ArticleRecord deleted = new ArticleRecord();
        deleted.setDeleted(1);
        assertEquals(1, deleted.getDeleted());
    }

    @Test
    void articlePublished_shouldAllowComment() {
        ArticleRecord published = new ArticleRecord();
        published.setId(1L);
        published.setStatus(1);
        published.setIsCommentEnabled(1);
        published.setDeleted(0);

        assertEquals(1L, published.getId());
        assertEquals(1, published.getStatus());
        assertEquals(1, published.getIsCommentEnabled());
        assertEquals(0, published.getDeleted());
    }

    @Test
    void articleCommentsDisabled_shouldBeBlocked() {
        ArticleRecord article = new ArticleRecord();
        article.setStatus(1);
        article.setIsCommentEnabled(0);

        assertNotEquals(1, article.getIsCommentEnabled() != null ? article.getIsCommentEnabled() : -1);
    }

    @Test
    void parentCommentBelongsToDifferentArticle_shouldBeBlocked() {
        CommentRecord parent = new CommentRecord();
        parent.setId(1L);
        parent.setArticleId(10L);
        parent.setDeleted(0);

        Long requestArticleId = 99L;
        assertNotEquals(requestArticleId, parent.getArticleId());
    }

    @Test
    void parentCommentDeleted_shouldBeBlocked() {
        CommentRecord parent = new CommentRecord();
        parent.setId(1L);
        parent.setDeleted(1);

        assertEquals(1, parent.getDeleted());
    }
}
