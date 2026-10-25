package com.blogsystem.application.administration.service;

import com.blogsystem.application.shared.support.PageUtil;
import com.blogsystem.domain.administration.query.DashboardSummary;
import com.blogsystem.domain.administration.query.OperationLogView;
import com.blogsystem.domain.administration.adapter.repository.AdministrationQueryRepository;
import com.blogsystem.types.model.PageResult;

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
        return repository.listLogs(
                module, PageUtil.clampPageNum(pageNum), PageUtil.clampPageSize(pageSize));
    }
}
