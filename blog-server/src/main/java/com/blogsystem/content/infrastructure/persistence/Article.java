package com.blogsystem.content.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("article")
public class Article {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long authorId;
    private String title;
    private String summary;
    private String contentMd;
    private String contentHtml;
    private String coverUrl;
    private Long categoryId;
    private Integer status;
    private Integer isTop;
    private Integer isCommentEnabled;
    private Integer viewCount;
    private Integer likeCount;
    private Integer collectCount;
    private LocalDateTime publishTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;

    @TableField(exist = false)
    private String categoryName;

    @TableField(exist = false)
    private List<String> tagNames;

    @TableField(exist = false)
    private List<Long> tagIds;
}
