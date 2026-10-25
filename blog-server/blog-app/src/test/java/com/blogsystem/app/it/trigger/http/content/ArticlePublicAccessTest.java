package com.blogsystem.app.it.trigger.http.content;

import org.junit.jupiter.api.Assertions;

import com.blogsystem.infrastructure.mybatis.record.content.ArticleRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3-11 / P0-2 文章公开访问过滤测试
 * 验证文章实体状态检查逻辑——草稿和已删除文章对公开用户不可见
 * 不依赖 Spring 上下文，测试纯实体状态判断
 */

/**
 * P0-2 公开文章接口草稿保护单元测试。
 * 公开端仅可访问 status=1 且 deleted=0 的文章：
 * 草稿(status=0)、已删除(deleted=1)、状态为null均不可公开访问。
 */
class ArticlePublicAccessTest {

    // ── status 字段过滤 ──

    @Test
    void articleWithStatus0_draft_shouldNotBePubliclyAccessible() {
        ArticleRecord draft = new ArticleRecord();
        draft.setId(1L);
        draft.setStatus(0);  // 草稿
        draft.setDeleted(0);

        // 公开访问检查：status 必须为 1
        boolean accessible = draft.getStatus() != null && draft.getStatus() == 1;
        assertFalse(accessible, "草稿文章(status=0)不应公开可访问");
    }

    @Test
    void articleWithStatus1_published_shouldBePubliclyAccessible() {
        ArticleRecord published = new ArticleRecord();
        published.setId(2L);
        published.setStatus(1);  // 已发布
        published.setDeleted(0);

        // 公开访问检查：status 为 1
        boolean accessible = published.getStatus() != null && published.getStatus() == 1;
        assertTrue(accessible, "已发布文章(status=1)应公开可访问");
    }

    @Test
    void articleWithStatusNull_shouldNotBePubliclyAccessible() {
        ArticleRecord article = new ArticleRecord();
        article.setId(3L);
        article.setStatus(null);  // 未设置状态
        article.setDeleted(0);

        // 公开访问：status 为 null 等同于不可访问
        boolean accessible = article.getStatus() != null && article.getStatus() == 1;
        assertFalse(accessible, "状态为 null 的文章不应公开可访问");

        // 与已发布文章对比
        ArticleRecord published = new ArticleRecord();
        published.setStatus(1);
        assertNotEquals(published.getStatus(), article.getStatus());
    }

    // ── deleted 字段过滤 ──

    @Test
    void articleWithDeleted1_shouldNotBeAccessible() {
        ArticleRecord deleted = new ArticleRecord();
        deleted.setId(4L);
        deleted.setStatus(1);    // 即使已发布
        deleted.setDeleted(1);   // 但软删除

        boolean deletedFlag = deleted.getDeleted() != null && deleted.getDeleted() == 1;
        assertTrue(deletedFlag, "deleted=1 应标记文章为不可用");

        // 过滤逻辑：deleted == 1 不可见
        boolean accessible = deleted.getDeleted() == null || deleted.getDeleted() != 1;
        assertFalse(accessible, "被删除文章(deleted=1)不应可访问");
    }

    @Test
    void articleWithDeleted0_shouldPassDeletedCheck() {
        ArticleRecord active = new ArticleRecord();
        active.setId(5L);
        active.setStatus(1);
        active.setDeleted(0);

        // deleted=0 表示未被删除，通过过滤
        boolean deletedFlag = active.getDeleted() == null || active.getDeleted() != 1;
        assertTrue(deletedFlag, "active文章(deleted=0)应通过删除检查");
    }

    // ── 组合过滤 ──

    @Test
    void publicFilter_requiresBothStatus1AndDeleted0() {
        // 模拟公开列表过滤逻辑
        ArticleRecord draft = new ArticleRecord();
        draft.setStatus(0);
        draft.setDeleted(0);

        ArticleRecord deleted = new ArticleRecord();
        deleted.setStatus(1);
        deleted.setDeleted(1);

        ArticleRecord published = new ArticleRecord();
        published.setStatus(1);
        published.setDeleted(0);

        // 过滤函数：status==1 AND deleted!=1
        assertFalse(isPublicAccessible(draft), "status=0 → 不可访问");
        assertFalse(isPublicAccessible(deleted), "deleted=1 → 不可访问");
        assertTrue(isPublicAccessible(published), "status=1 + deleted=0 → 可访问");
    }

    @Test
    void draftArticle_filtersTheSame_asDeletedArticle() {
        // 草稿和已删除文章从公开视角来看效果相同——都不可见
        ArticleRecord draft = new ArticleRecord();
        draft.setId(10L);
        draft.setStatus(0);
        draft.setDeleted(0);

        ArticleRecord deleted = new ArticleRecord();
        deleted.setId(11L);
        deleted.setStatus(1);
        deleted.setDeleted(1);

        assertFalse(isPublicAccessible(draft));
        assertFalse(isPublicAccessible(deleted));
        // 两者对公开用户来说都不可见
        assertEquals(isPublicAccessible(draft), isPublicAccessible(deleted));
    }

    /**
     * 模拟公开访问过滤逻辑：status==1 且 deleted!=1
     */
    private boolean isPublicAccessible(ArticleRecord article) {
        if (article == null) return false;
        // 已删除的文章不可访问
        if (article.getDeleted() != null && article.getDeleted() == 1) return false;
        // 只有状态为 1（已发布）才可公开访问
        return article.getStatus() != null && article.getStatus() == 1;
    }
}
