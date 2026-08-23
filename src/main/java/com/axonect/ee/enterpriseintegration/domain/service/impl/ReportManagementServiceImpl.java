package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.constant.LoggingAdviceConstants;
import com.axonect.ee.enterpriseintegration.application.transport.request.FilterRequest;
import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.PagedReportDetails;
import com.axonect.ee.enterpriseintegration.application.util.exception.StackTraceTracker;
import com.axonect.ee.enterpriseintegration.application.util.exception.type.BaseException;
import com.axonect.ee.enterpriseintegration.application.util.resultenum.ResponseCodeEnum;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.repository.DownloadReportRepository;
import com.axonect.ee.enterpriseintegration.domain.service.ReportManagementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ReportManagementServiceImpl implements ReportManagementService {

    private final DownloadReportRepository downloadReportRepository ;



    public ReportManagementServiceImpl(DownloadReportRepository downloadReportRepository) {
        this.downloadReportRepository = downloadReportRepository;

    }


    @Override
    public CommonAdaptorResp<PagedReportDetails> filterReports(FilterRequest filterRequest) throws BaseException {
        long startTime = System.currentTimeMillis();
        try {

            Pageable pageable = PageRequest.of(
                    filterRequest.getPage() - 1,   // page is 0-based internally
                    filterRequest.getPageSize(),
                    Sort.by(Sort.Direction.DESC, "createdAt")
            );

            Page<DownloadReport> page = downloadReportRepository.findFilteredReports(
                    null,
                    filterRequest.getReportType(),
                    filterRequest.getReportStatus(),
                    filterRequest.getStartDate(),
                    filterRequest.getEndDate(),
                    pageable
            );

            PagedReportDetails data =  PagedReportDetails.builder()
                    .page(page.getNumber() + 1)
                    .pageSize(page.getSize())
                    .totalRecords(page.getTotalElements())
                    .reportDetails(page.getContent())
                    .build();

            CommonAdaptorResp<PagedReportDetails> response = new CommonAdaptorResp<>();
            response.setSuccess(true);
            response.setMessage(ResponseCodeEnum.SUCCESS.message());
            response.setData(data);

            return response;
        } catch (Exception e) {
            log.error(LoggingAdviceConstants.EXCEPTION_STACK_TRACE,
                    System.currentTimeMillis() - startTime,
                    e.getMessage(),
                    StackTraceTracker.displayStackStraceArray(e.getStackTrace()));
            throw new BaseException(ResponseCodeEnum.INTERNAL_SERVER_ERROR.code(),
                    ResponseCodeEnum.INTERNAL_SERVER_ERROR.message());
        }
    }

}
