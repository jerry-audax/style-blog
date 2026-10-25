package com.blogsystem.infrastructure.mybatis.record.content;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("article_tag")
public class ArticleTagRecord {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long articleId;
    private Long tagId;
}
