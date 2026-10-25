package com.blogsystem.infrastructure.mybatis.record.content;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tag")
public class TagRecord {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String slug;
    private String color;
    private Integer sort;
    private Integer status;
    private Integer isHot;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
}
