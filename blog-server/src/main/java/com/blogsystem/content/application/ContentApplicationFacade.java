package com.blogsystem.content.application;

import com.blogsystem.content.application.command.SaveArticleCommand;
import com.blogsystem.content.domain.model.*;
import com.blogsystem.content.domain.query.*;
import com.blogsystem.content.domain.repository.*;
import com.blogsystem.shared.domain.PageResult;
import com.blogsystem.shared.domain.port.CurrentActor;
import com.blogsystem.shared.application.PageUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
public class ContentApplicationFacade {
    private final ArticleCommandService commands;
    private final ArticleRepository articles;
    private final ContentQueryRepository queries;
    private final TaxonomyRepository taxonomy;
    private final ArticleEngagementRepository engagement;
    private final CurrentActor actor;
    private final Clock clock;

    public ContentApplicationFacade(ArticleCommandService commands, ArticleRepository articles,
                                    ContentQueryRepository queries, TaxonomyRepository taxonomy, ArticleEngagementRepository engagement,
                                    CurrentActor actor, Clock clock) {
        this.commands = commands;
        this.articles = articles;
        this.queries = queries;
        this.taxonomy = taxonomy;
        this.engagement = engagement;
        this.actor = actor;
        this.clock = clock;
    }

    public Long saveArticle(SaveArticleCommand command) {
        return commands.save(command, actor.requireUserId(), clock.instant()).id().value();
    }

    public PageResult<ArticleView> listArticles(Integer status, Long categoryId, Long tagId, long pageNum, long pageSize) {
        return queries.listArticles(status, categoryId, tagId, PageUtil.clampPageNum(pageNum), PageUtil.clampPageSize(pageSize));
    }

    public List<ArticleView> listHotArticles(long limit) {
        return queries.listHotArticles(PageUtil.clampPageSize(limit)).stream().filter(ArticleView::publiclyVisible).toList();
    }

    public ArticleView getArticle(Long id, boolean includeDrafts) {
        ArticleView article = queries.findArticle(id, includeDrafts)
                .filter(row -> Integer.valueOf(0).equals(row.deleted()))
                .filter(row -> includeDrafts || row.publiclyVisible())
                .orElseThrow(() -> new IllegalArgumentException("文章不存在"));
        return article.withViewCount(queries.incrementViews(id, includeDrafts));
    }

    @Transactional
    public void deleteArticle(Long id) {
        articles.delete(new ArticleId(id));
    }

    @Transactional
    public Long saveCategory(CategoryDefinition category) {
        return taxonomy.saveCategory(category);
    }

    public List<CategoryView> listCategory() {
        return taxonomy.listCategory();
    }

    @Transactional
    public void deleteCategory(Long id) {
        taxonomy.deleteCategory(id);
    }

    @Transactional
    public Long saveTag(TagDefinition tag) {
        return taxonomy.saveTag(tag);
    }

    public List<TagView> listTag() {
        return taxonomy.listTag();
    }

    @Transactional
    public void deleteTag(Long id) {
        taxonomy.deleteTag(id);
    }

    @Transactional
    public boolean toggleLike(Long id) {
        return engagement.toggleLike(id, actor.requireUserId());
    }
}
