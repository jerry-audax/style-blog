package com.blogsystem.content.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

import com.blogsystem.content.application.command.SaveArticleCommand;
import com.blogsystem.content.domain.model.*;

public record ArticleSaveRequest(
        Long id,
        @NotBlank(message = "标题不能为空")
        String title,
        String summary,
        @NotBlank(message = "内容不能为空")
        String contentMd,
        String coverUrl,
        Long categoryId,
        @NotNull(message = "状态不能为空")
        Integer status,
        Integer isTop,
        Integer isCommentEnabled,
        List<Long> tagIds
) {
    public SaveArticleCommand toCommand() {
        ContentFormat format = contentMd != null && contentMd.stripLeading().startsWith("<")
                ? ContentFormat.HTML : ContentFormat.MARKDOWN;
        if (status == null) throw new IllegalArgumentException("状态不能为空");
        return new SaveArticleCommand(id, new ArticleRevision(title, summary, new ContentBody(contentMd, format),
                coverUrl, categoryId, ArticleStatus.fromLegacyValue(status), isTop == null ? 0 : isTop,
                isCommentEnabled == null ? 1 : isCommentEnabled, tagIds));
    }
}
