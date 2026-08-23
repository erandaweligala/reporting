package com.axonect.ee.enterpriseintegration.application.controller;

import com.axonect.ee.enterpriseintegration.application.constant.AppConstant;
import com.axonect.ee.enterpriseintegration.application.constant.LoggingAdviceConstants;
import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.util.exception.type.BaseException;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReportRequest;
import com.axonect.ee.enterpriseintegration.domain.service.impl.DownloadReportServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;


@RestController
@RequestMapping("api/report-download")
@Slf4j

public class DownloadReportController{

    private  final DownloadReportServiceImpl downloadReportService;

    public DownloadReportController(DownloadReportServiceImpl downloadReportService){
        this.downloadReportService = downloadReportService;
    }

    @PostMapping("/create")
    public ResponseEntity<CommonAdaptorResp<Void>> generateReportRequest(@RequestBody DownloadReportRequest downloadReportRequest,HttpServletRequest httpRequest, @RequestHeader(AppConstant.HEADER_USER_ID) String userId) throws BaseException {
        long startTime = System.currentTimeMillis();
        log.info(LoggingAdviceConstants.REQUEST_INITIATED, httpRequest.getMethod(), httpRequest.getRequestURI());
        downloadReportRequest.setCreatedBy(userId);
        CommonAdaptorResp<Void> resp = downloadReportService.createDownloadReportRequest(downloadReportRequest);
        log.info(LoggingAdviceConstants.REQUEST_TERMINATED, System.currentTimeMillis() - startTime, resp.getMessage());
        return ResponseEntity.ok(resp);
    }


    @GetMapping("/download")
    public ResponseEntity<Resource> downloadReport(
            @RequestParam(name = "id") Long id,
            HttpServletRequest httpServletRequest) throws BaseException, IOException {
        Resource reportResource = downloadReportService.getReportFile(id, httpServletRequest);
        String filename = downloadReportService.getReportFilename(id);

        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"));

        long contentLength = reportResource.contentLength();
        if (contentLength >= 0) {
            responseBuilder.contentLength(contentLength);
        }

        return responseBuilder.body(reportResource);
    }

}


