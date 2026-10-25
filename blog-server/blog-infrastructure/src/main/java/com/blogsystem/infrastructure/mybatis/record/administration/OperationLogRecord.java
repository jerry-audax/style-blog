package com.blogsystem.infrastructure.mybatis.record.administration;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("operation_log")
public class OperationLogRecord {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String module;
    private String action;
    private String content;
    private String requestData;
    private String ip;
    private String userAgent;
    private LocalDateTime createdAt;
}
