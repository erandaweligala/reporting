package com.axonect.ee.enterpriseintegration.domain.client;


import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.exception.ReportClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class AuditLogAdaptorTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private AuditLogAdaptor auditLogAdaptor;

    private DownloadReport report;

    @BeforeEach
    void setup() {
        report = new DownloadReport();
        ReflectionTestUtils.setField(auditLogAdaptor, "auditLogBaseUrl", "http://audit-log/api");
    }

    // -------------------- SUCCESS CASE --------------------

    @Test
    void fetchAuditLogs_success() {
        report.setFilterValuesJson(
                "[{\"columnName\":\"status\",\"value\":[\"SUCCESS\"]}]"
        );

        // Page detail inside result
        Map<String, Object> pageDetail = Map.of(
                "pageNumber", 1,
                "pageElementCount", 10,
                "totalRecords", 1
        );

        // Result block
        Map<String, Object> resultBlock = new HashMap<>();
        resultBlock.put("resultCode", "00");
        resultBlock.put("resultDescription", "SUCCESSFUL");
        resultBlock.put("pageDetail", pageDetail);

        // Full response body
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("result", resultBlock);
        responseBody.put("responseData", List.of(Map.of("id", 1)));

        ResponseEntity<Map<String, Object>> response =
                new ResponseEntity<>(responseBody, HttpStatus.OK);

        Mockito.when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)
        )).thenReturn(response);

        CommonAdaptorResp<FilterListViewBatch> result =
                auditLogAdaptor.fetchAuditLogs(report, 0, 10);

        assertTrue(result.isSuccess());
        assertEquals("SUCCESSFUL", result.getMessage());
        assertNotNull(result.getData());
        assertEquals(1, result.getData().getTableData().size());
        assertEquals(1, result.getData().getPage());
        assertEquals(10, result.getData().getPageSize());
        assertEquals(1, result.getData().getTotalRecords());
    }


    // -------------------- BODY NULL --------------------

    @Test
    void fetchAuditLogs_bodyNull_shouldThrowException() {
        ResponseEntity<Map<String, Object>> response =
                new ResponseEntity<>(null, HttpStatus.OK);

        Mockito.when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)
        )).thenReturn(response);

        assertThrows(ReportClientException.class,
                () -> auditLogAdaptor.fetchAuditLogs(report, 0, 10));
    }

    // -------------------- NON-00 RESPONSE CODE --------------------

    @Test
    void fetchAuditLogs_nonSuccessCode_shouldThrowException() {
        Map<String, Object> responseBody = Map.of(
                "code", "99",
                "message", "Failure"
        );

        ResponseEntity<Map<String, Object>> response =
                new ResponseEntity<>(responseBody, HttpStatus.OK);

        Mockito.when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)
        )).thenReturn(response);

        assertThrows(ReportClientException.class,
                () -> auditLogAdaptor.fetchAuditLogs(report, 0, 10));
    }

    // -------------------- HTTP STATUS EXCEPTION --------------------

    @Test
    void fetchAuditLogs_httpStatusException() {
        Mockito.when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)
        )).thenThrow(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                HttpHeaders.EMPTY,
                "error".getBytes(),
                null
        ));

        assertThrows(ReportClientException.class,
                () -> auditLogAdaptor.fetchAuditLogs(report, 0, 10));
    }

    // -------------------- REST CLIENT EXCEPTION --------------------

    @Test
    void fetchAuditLogs_restClientException() {
        Mockito.when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)
        )).thenThrow(new RestClientException("Connection error"));

        assertThrows(ReportClientException.class,
                () -> auditLogAdaptor.fetchAuditLogs(report, 0, 10));
    }

    // -------------------- IO EXCEPTION (JSON PARSE ERROR) --------------------

    @Test
    void fetchAuditLogs_invalidFilterJson_shouldThrowIOException() {
        report.setFilterValuesJson("INVALID_JSON");

        assertThrows(ReportClientException.class,
                () -> auditLogAdaptor.fetchAuditLogs(report, 0, 10));
    }

    // -------------------- GENERIC EXCEPTION --------------------

    @Test
    void fetchAuditLogs_genericException() throws Exception {
        report.setFilterValuesJson("[{}]");

        // Spy to force unexpected exception
        AuditLogAdaptor spy = Mockito.spy(auditLogAdaptor);

        Mockito.doThrow(new RuntimeException("Boom"))
                .when(spy)
                .fetchAuditLogs(any(), anyInt(), anyInt());

        assertThrows(RuntimeException.class,
                () -> spy.fetchAuditLogs(report, 0, 10));
    }
}

