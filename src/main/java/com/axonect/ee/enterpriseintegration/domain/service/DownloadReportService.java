package com.axonect.ee.enterpriseintegration.domain.service;

import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.util.exception.type.BaseException;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReportRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;


public interface DownloadReportService {

    CommonAdaptorResp<Void> createDownloadReportRequest(DownloadReportRequest request) throws BaseException;
    Resource getReportFile(Long reportId, HttpServletRequest httpServletRequest ) throws BaseException;
    String getReportFilename(Long reportId) throws BaseException;
}


