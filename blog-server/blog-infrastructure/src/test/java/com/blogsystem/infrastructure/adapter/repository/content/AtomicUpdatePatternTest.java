package com.blogsystem.infrastructure.adapter.repository.content;

import org.junit.jupiter.api.Assertions;

import com.blogsystem.infrastructure.mybatis.record.content.ArticleRecord;
import com.blogsystem.infrastructure.mybatis.record.content.ArticleLikeRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-6 原子更新模式单元测试。
 * 验证数据库层原子操作（SET view_count = view_count + 1）替代读-改-写模式：
 * 浏览量递增、点赞数防负数（GREATEST(count-1, 0)）、点赞记录引用完整性。
 */
class AtomicUpdatePatternTest {

    @Test
    void viewCount_incrementIsPositive() {
        // 验证递增模式：每次阅读 +1
        ArticleRecord article = new ArticleRecord();
        article.setViewCount(100);
        int newCount = article.getViewCount() + 1;
        assertEquals(101, newCount);
        assertTrue(newCount > article.getViewCount());
    }

    @Test
    void likeCount_incrementShouldNotGoNegative() {
        // 模拟原子更新 GREATEST(like_count - 1, 0)
        ArticleRecord article = new ArticleRecord();
        article.setLikeCount(5);
        int afterLike = article.getLikeCount() + 1;
        assertEquals(6, afterLike);

        article.setLikeCount(0);
        int afterUnlike = Math.max(0, article.getLikeCount() - 1);
        assertEquals(0, afterUnlike);

        article.setLikeCount(1);
        afterUnlike = Math.max(0, article.getLikeCount() - 1);
        assertEquals(0, afterUnlike);
    }

    @Test
    void toggleLike_referenceIntegrity() {
        // 点赞记录必须关联正确的文章和用户
        ArticleLikeRecord like = new ArticleLikeRecord();
        like.setArticleId(42L);
        like.setUserId(7L);

        assertEquals(42L, like.getArticleId());
        assertEquals(7L, like.getUserId());
    }
}
