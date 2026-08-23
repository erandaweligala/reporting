package com.axonect.ee.enterpriseintegration.application.controller;

import com.axonect.ee.enterpriseintegration.application.constant.LoggingAdviceConstants;
import com.axonect.ee.enterpriseintegration.application.transport.request.FilterRequest;
import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.PagedReportDetails;
import com.axonect.ee.enterpriseintegration.application.util.exception.type.BaseException;
import com.axonect.ee.enterpriseintegration.domain.service.impl.ReportManagementServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/report-management")
@Slf4j

public class ReportManagementController {

    private final ReportManagementServiceImpl reportManagementService;

    public ReportManagementController(ReportManagementServiceImpl reportManagementService) {
        this.reportManagementService = reportManagementService;
    }


    @PostMapping("/filter")
    public ResponseEntity<CommonAdaptorResp<PagedReportDetails>> filterReports(@RequestBody FilterRequest filterRequest, HttpServletRequest httpServletRequest) throws BaseException {
        long startTime = System.currentTimeMillis();
        log.info(LoggingAdviceConstants.REQUEST_INITIATED, httpServletRequest.getMethod(), httpServletRequest.getRequestURI());
        CommonAdaptorResp<PagedReportDetails> resp = reportManagementService.filterReports(filterRequest);
        log.info(LoggingAdviceConstants.REQUEST_TERMINATED, System.currentTimeMillis() - startTime, resp.getMessage());
        return ResponseEntity.status(HttpStatus.OK).body(resp);
    }

}

