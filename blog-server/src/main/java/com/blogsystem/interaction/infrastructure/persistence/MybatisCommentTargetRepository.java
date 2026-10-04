package com.blogsystem.interaction.infrastructure.persistence;

import com.blogsystem.content.infrastructure.persistence.ArticleMapper;
import com.blogsystem.interaction.domain.model.CommentTarget;
import com.blogsystem.interaction.domain.repository.CommentTargetRepository;

import java.util.Optional;

import org.springframework.stereotype.Repository;

@Repository
public class MybatisCommentTargetRepository implements CommentTargetRepository {
    private final ArticleMapper articles;

    public MybatisCommentTargetRepository(ArticleMapper articles) {
        this.articles = articles;
    }

    @Override
    public Optional<CommentTarget> findTarget(Long id) {
        return Optional.ofNullable(articles.selectById(id)).filter(a -> Integer.valueOf(0).equals(a.getDeleted()))
                .map(a -> new CommentTarget(id, Integer.valueOf(1).equals(a.getStatus()),
                        Integer.valueOf(1).equals(a.getIsCommentEnabled())));
    }
}
