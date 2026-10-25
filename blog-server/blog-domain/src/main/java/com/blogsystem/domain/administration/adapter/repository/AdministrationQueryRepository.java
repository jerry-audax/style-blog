package com.blogsystem.domain.administration.adapter.repository;

import com.blogsystem.domain.administration.query.DashboardSummary;
import com.blogsystem.domain.administration.query.OperationLogView;
import com.blogsystem.types.model.PageResult;

public interface AdministrationQueryRepository {
    DashboardSummary getDashboardStats();

    PageResult<OperationLogView> listLogs(String module, long pageNum, long pageSize);
}
