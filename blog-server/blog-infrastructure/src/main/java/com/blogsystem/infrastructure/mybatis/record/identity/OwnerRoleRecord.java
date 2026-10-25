package com.blogsystem.infrastructure.mybatis.record.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("sys_role")
public class OwnerRoleRecord {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String roleCode;
    private String roleName;
    private Integer status;
    private String remark;
    private Integer deleted;
}
