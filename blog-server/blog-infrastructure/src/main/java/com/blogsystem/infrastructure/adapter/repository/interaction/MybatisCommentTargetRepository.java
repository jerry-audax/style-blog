package com.blogsystem.infrastructure.adapter.repository.interaction;

import com.blogsystem.domain.interaction.model.CommentTarget;
import com.blogsystem.domain.interaction.adapter.repository.CommentTargetRepository;
import com.blogsystem.infrastructure.mybatis.mapper.content.ArticleMapper;

import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class MybatisCommentTargetRepository implements CommentTargetRepository {
    private final ArticleMapper articles;

    public MybatisCommentTargetRepository(ArticleMapper articles) {
        this.articles = articles;
    }

    @Override
    public Optional<CommentTarget> findTarget(Long id) {
        return Optional.ofNullable(articles.selectById(id))
                .filter(a -> Integer.valueOf(0).equals(a.getDeleted()))
                .map(
                        a ->
                                new CommentTarget(
                                        id,
                                        Integer.valueOf(1).equals(a.getStatus()),
                                        Integer.valueOf(1).equals(a.getIsCommentEnabled())));
    }
}
