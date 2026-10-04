package com.blogsystem.identity.infrastructure.security;

import cn.dev33.satoken.stp.StpUtil;
import com.blogsystem.shared.domain.port.CurrentActor;
import org.springframework.stereotype.Component;

@Component
public class SaTokenCurrentActor implements CurrentActor {
    @Override
    public long requireUserId() {
        return StpUtil.getLoginIdAsLong();
    }
}

