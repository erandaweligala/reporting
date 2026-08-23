package com.axonect.ee.enterpriseintegration.domain.service;

import com.axonect.ee.enterpriseintegration.application.transport.request.FilterRequest;
import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.PagedReportDetails;
import com.axonect.ee.enterpriseintegration.application.util.exception.type.BaseException;

public interface ReportManagementService {

    CommonAdaptorResp<PagedReportDetails> filterReports(FilterRequest filterRequest) throws BaseException;

}