package com.blogsystem.infrastructure.security.identity;

import cn.dev33.satoken.stp.StpUtil;

import com.blogsystem.domain.shared.adapter.port.CurrentActor;

import org.springframework.stereotype.Component;

@Component
public class SaTokenCurrentActor implements CurrentActor {
    @Override
    public long requireUserId() {
        return StpUtil.getLoginIdAsLong();
    }
}
