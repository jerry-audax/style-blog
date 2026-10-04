package com.blogsystem.administration.domain.repository;

import com.blogsystem.administration.domain.query.DashboardSummary;
import com.blogsystem.administration.domain.query.OperationLogView;
import com.blogsystem.shared.domain.PageResult;

public interface AdministrationQueryRepository {
    DashboardSummary getDashboardStats();

    PageResult<OperationLogView> listLogs(String module, long pageNum, long pageSize);
}

