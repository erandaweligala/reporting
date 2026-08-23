package com.axonect.ee.enterpriseintegration.domain.client;

import com.axonect.ee.enterpriseintegration.application.transport.request.FilterValue;
import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionHistoryAdaptorTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private ObjectMapper objectMapper;

    private SessionHistoryAdaptor sessionHistoryAdaptor;

    private final String baseUrl = "http://test-session-history.com";

    @BeforeEach
    void setUp() {
        sessionHistoryAdaptor = new SessionHistoryAdaptor(restTemplate);
        ReflectionTestUtils.setField(sessionHistoryAdaptor, "sessionHistoryBaseUrl", baseUrl);
        ReflectionTestUtils.setField(sessionHistoryAdaptor, "objectMapper", objectMapper);
    }

    @Test
    void constructor_ShouldInitializeWithRestTemplate() {
        RestTemplate testRestTemplate = mock(RestTemplate.class);
        SessionHistoryAdaptor adaptor = new SessionHistoryAdaptor(testRestTemplate);
        assertNotNull(adaptor);
    }

    @Test
    void fetchSessionHistory_WithValidRequest_ShouldReturnSuccessfulResponse() throws Exception {
        // Given
        DownloadReport report = createReportWithFilters();
        int offset = 0;
        int limit = 10;

        List<FilterValue> filters = createFilterValues();
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(filters);

        ResponseEntity<Map<String, Object>> responseEntity =
                new ResponseEntity<>(createSuccessResponseBody(), HttpStatus.OK);

        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                isNull(),
                any(ParameterizedTypeReference.class))
        ).thenReturn(responseEntity);

        // When
        CommonAdaptorResp<FilterListViewBatch> result =
                sessionHistoryAdaptor.fetchSessionHistory(report, offset, limit);

        // Then
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("Success", result.getMessage());

        FilterListViewBatch batch = result.getData();
        assertNotNull(batch);
        assertEquals(2, batch.getTableData().size());
        assertEquals(1, batch.getPage());
        assertEquals(10, batch.getPageSize());
        assertEquals(50, batch.getTotalRecords());

        // Verify URL
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(
                urlCaptor.capture(),
                eq(HttpMethod.GET),
                isNull(),
                any(ParameterizedTypeReference.class)
        );

        String url = urlCaptor.getValue();
        assertTrue(url.contains("page=1"));
        assertTrue(url.contains("pageSize=10"));
        assertTrue(url.contains("userId=123"));
        assertTrue(url.contains("status=ACTIVE"));
    }

    @Test
    void fetchSessionHistory_WithNullFilters_ShouldOnlyUsePaginationParams() {
        // Given
        DownloadReport report = createReportWithoutFilters();

        ResponseEntity<Map<String, Object>> responseEntity =
                new ResponseEntity<>(createSuccessResponseBody(), HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(responseEntity);

        // When
        CommonAdaptorResp<FilterListViewBatch> result =
                sessionHistoryAdaptor.fetchSessionHistory(report, 0, 10);

        // Then
        assertTrue(result.isSuccess());

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class));

        String url = urlCaptor.getValue();
        assertTrue(url.contains("page=1"));
        assertTrue(url.contains("pageSize=10"));
        assertFalse(url.contains("userId="));
    }

    @Test
    void fetchSessionHistory_WithNullFilterValues_ShouldSkipThoseFilters() throws Exception {
        // Given
        DownloadReport report = createReportWithFilters();

        List<FilterValue> filters = Arrays.asList(
                new FilterValue("userId", "123",null),
                new FilterValue("device", null,null)
        );

        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(filters);

        ResponseEntity<Map<String, Object>> responseEntity =
                new ResponseEntity<>(createSuccessResponseBody(), HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(responseEntity);

        // When
        sessionHistoryAdaptor.fetchSessionHistory(report, 0, 10);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class));

        String url = urlCaptor.getValue();
        assertTrue(url.contains("userId=123"));
        assertFalse(url.contains("device="));
    }

    @Test
    void fetchSessionHistory_WithOffset_ShouldCalculateCorrectPage() {
        // Given
        DownloadReport report = createReportWithoutFilters();

        ResponseEntity<Map<String, Object>> responseEntity =
                new ResponseEntity<>(createSuccessResponseBody(), HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(responseEntity);

        // When
        sessionHistoryAdaptor.fetchSessionHistory(report, 30, 10);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class));

        assertTrue(urlCaptor.getValue().contains("page=4"));
    }


    @Test
    void fetchSessionHistory_WithNonSuccessStatus_ShouldThrowException() {
        // Given
        DownloadReport report = createReportWithoutFilters();

        Map<String, Object> body = new HashMap<>();
        body.put("status", "99");
        body.put("message", "Failure");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        // When & Then
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> sessionHistoryAdaptor.fetchSessionHistory(report, 0, 10));

        assertFalse(ex.getMessage().contains("Failed to fetch Session History"));
    }

    @Test
    void fetchSessionHistory_WithHttpClientError_ShouldThrowRuntimeException() {
        // Given
        DownloadReport report = createReportWithoutFilters();

        HttpClientErrorException ex =
                new HttpClientErrorException(HttpStatus.BAD_REQUEST);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenThrow(ex);

        // When & Then
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> sessionHistoryAdaptor.fetchSessionHistory(report, 0, 10));

        assertTrue(thrown.getMessage().contains("CDR management service error"));
        assertEquals(ex, thrown.getCause());
    }

    @Test
    void fetchSessionHistory_WithHttpServerError_ShouldThrowRuntimeException() {
        // Given
        DownloadReport report = createReportWithoutFilters();

        HttpServerErrorException ex =
                new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenThrow(ex);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> sessionHistoryAdaptor.fetchSessionHistory(report, 0, 10));

        assertTrue(thrown.getMessage().contains("CDR management service error"));
        assertEquals(ex, thrown.getCause());
    }

    @Test
    void fetchSessionHistory_WithRestClientException_ShouldThrowRuntimeException() {
        // Given
        DownloadReport report = createReportWithoutFilters();

        ResourceAccessException ex = new ResourceAccessException("Timeout");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenThrow(ex);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> sessionHistoryAdaptor.fetchSessionHistory(report, 0, 10));

        assertTrue(thrown.getMessage().contains("Unable to connect to CDR management service"));
        assertEquals(ex, thrown.getCause());
    }

    @Test
    void fetchSessionHistory_WithInvalidJSON_ShouldThrowRuntimeException() throws Exception {
        // Given
        DownloadReport report = createReportWithFilters();

        IOException ex = new JsonProcessingException("Invalid JSON") {};

        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenThrow(ex);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> sessionHistoryAdaptor.fetchSessionHistory(report, 0, 10));

        assertTrue(thrown.getMessage().contains("Invalid filter values JSON"));
        assertEquals(ex, thrown.getCause());
    }

    /* ---------------- helper methods ---------------- */

    private DownloadReport createReportWithFilters() {
        DownloadReport report = new DownloadReport();
        report.setFilterValuesJson(
                "[{\"columnName\":\"userId\",\"value\":\"123\"}," +
                        "{\"columnName\":\"status\",\"value\":\"ACTIVE\"}]"
        );
        return report;
    }

    private DownloadReport createReportWithoutFilters() {
        DownloadReport report = new DownloadReport();
        report.setFilterValuesJson(null);
        return report;
    }

    private List<FilterValue> createFilterValues() {
        return Arrays.asList(
                new FilterValue("userId", "123",null),
                new FilterValue("status", "ACTIVE",null)
        );
    }

    private Map<String, Object> createSuccessResponseBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "00");
        body.put("message", "Success");

        Map<String, Object> pageDetails = new HashMap<>();
        pageDetails.put("pageNumber", 1);
        pageDetails.put("pageElementCount", 10);
        pageDetails.put("totalRecords", 50);

        body.put("pageDetails", pageDetails);
        body.put("data", Arrays.asList(
                Map.of("sessionId", "S1"),
                Map.of("sessionId", "S2")
        ));

        return body;
    }
}
