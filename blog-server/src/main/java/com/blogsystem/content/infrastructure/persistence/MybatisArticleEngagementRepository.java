package com.blogsystem.content.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.blogsystem.content.domain.repository.ArticleEngagementRepository;
import com.blogsystem.content.infrastructure.cache.HotArticleCache;
import org.springframework.stereotype.Repository;

/**
 * An atomic persistence operation; the application owns its transaction.
 */
@Repository
public class MybatisArticleEngagementRepository implements ArticleEngagementRepository {
    private final ArticleMapper articles;
    private final ArticleLikeMapper likes;
    private final HotArticleCache cache;

    public MybatisArticleEngagementRepository(ArticleMapper articles, ArticleLikeMapper likes, HotArticleCache cache) {
        this.articles = articles;
        this.likes = likes;
        this.cache = cache;
    }

    @Override
    public boolean toggleLike(Long articleId, long userId) {
        // Serialize concurrent toggles for this article. The existing unique(article_id,user_id) remains final guard.
        if (articles.selectActiveForUpdate(articleId) == null) throw new IllegalArgumentException("文章不存在");
        ArticleLike existing = likes.selectOne(new LambdaQueryWrapper<ArticleLike>()
                .eq(ArticleLike::getArticleId, articleId).eq(ArticleLike::getUserId, userId));
        boolean liked = existing == null;
        if (liked) {
            ArticleLike like = new ArticleLike();
            like.setArticleId(articleId);
            like.setUserId(userId);
            if (likes.insert(like) != 1) throw new IllegalStateException("点赞保存失败");
        } else if (likes.deleteById(existing.getId()) != 1) throw new IllegalStateException("点赞删除失败");
        String sql = liked ? "like_count = COALESCE(like_count, 0) + 1"
                : "like_count = GREATEST(COALESCE(like_count, 0) - 1, 0)";
        if (articles.update(null, new LambdaUpdateWrapper<Article>().eq(Article::getId, articleId)
                .eq(Article::getDeleted, 0).setSql(sql)) != 1) throw new IllegalArgumentException("文章不存在");
        cache.evictAfterCommit();
        return liked;
    }
}
