package com.blogsystem.infrastructure.mybatis.record.administration;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("login_log")
public class LoginLogRecord {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String username;
    private Integer loginStatus;
    private String loginType;
    private String ip;
    private String userAgent;
    private String failReason;
    private LocalDateTime createdAt;
}
