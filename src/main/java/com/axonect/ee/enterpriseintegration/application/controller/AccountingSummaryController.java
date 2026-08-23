package com.axonect.ee.enterpriseintegration.application.controller;

import com.axonect.ee.enterpriseintegration.application.constant.LoggingAdviceConstants;
import com.axonect.ee.enterpriseintegration.domain.service.impl.AccountingSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.axonect.ee.enterpriseintegration.application.controller.BaseController.ApiResponse;

@Slf4j
@RestController
@RequestMapping("/api/v1/accounting")
@RequiredArgsConstructor
public class AccountingSummaryController {

    private final AccountingSummaryService accountingSummaryService;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse> getAccountingSummary() {

        log.info(LoggingAdviceConstants.REQUEST_INITIATED,
                "GET", "/api/v1/accounting/summary");

        ApiResponse response = accountingSummaryService.getAccountingSummary();

        log.info(LoggingAdviceConstants.REQUEST_TERMINATED,
                "SUCCESS", "Summary response returned");

        return ResponseEntity.ok(response);
    }
}
