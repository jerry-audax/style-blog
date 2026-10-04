package com.blogsystem.administration.application;

import com.blogsystem.administration.domain.repository.AdministrationQueryRepository;
import com.blogsystem.administration.domain.query.*;
import com.blogsystem.shared.domain.PageResult;
import com.blogsystem.shared.application.PageUtil;
import org.springframework.stereotype.Service;

@Service
public class AdministrationApplicationFacade {
    private final AdministrationQueryRepository repository;

    public AdministrationApplicationFacade(AdministrationQueryRepository repository) {
        this.repository = repository;
    }

    public DashboardSummary dashboard() {
        return repository.getDashboardStats();
    }

    public PageResult<OperationLogView> logs(String module, long pageNum, long pageSize) {
        return repository.listLogs(module, PageUtil.clampPageNum(pageNum), PageUtil.clampPageSize(pageSize));
    }
}
