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
class MessageLogAdaptorTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private ObjectMapper objectMapper;

    private MessageLogAdaptor messageLogAdaptor;

    private final String baseUrl = "http://test-message-log.com";

    @BeforeEach
    void setUp() {
        messageLogAdaptor = new MessageLogAdaptor(restTemplate);
        ReflectionTestUtils.setField(messageLogAdaptor, "messageLogBaseUrl", baseUrl);
        ReflectionTestUtils.setField(messageLogAdaptor, "objectMapper", objectMapper);
    }

    @Test
    void constructor_ShouldInitializeWithRestTemplate() {
        // Given
        RestTemplate testRestTemplate = mock(RestTemplate.class);

        // When
        MessageLogAdaptor adaptor = new MessageLogAdaptor(testRestTemplate);

        // Then
        assertNotNull(adaptor);
    }

    @Test
    void fetchActionLogs_WithValidRequestAndResponse_ShouldReturnSuccessfulResult() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithFilters();
        int offset = 0;
        int limit = 10;

        List<FilterValue> filters = createFilterValues();
        when(objectMapper.readValue(anyString(), any(TypeReference.class))).thenReturn(filters);

        Map<String, Object> responseBody = createSuccessfulResponseBody();
        ResponseEntity<Map<String, Object>> responseEntity = new ResponseEntity<>(responseBody, HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(responseEntity);

        // When
        CommonAdaptorResp<FilterListViewBatch> result = messageLogAdaptor.fetchActionLogs(report, offset, limit);

        // Then
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("Success", result.getMessage());
        assertNotNull(result.getData());

        FilterListViewBatch batch = result.getData();
        assertEquals(2, batch.getTableData().size());
        assertEquals(1, batch.getPage());
        assertEquals(10, batch.getPageSize());
        assertEquals(2, batch.getTotalRecords());

        // Verify URL construction with filters
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class));

        String capturedUrl = urlCaptor.getValue();
        assertTrue(capturedUrl.contains("page=1"));
        assertTrue(capturedUrl.contains("page_size=10"));
        assertTrue(capturedUrl.contains("status=active"));
        assertTrue(capturedUrl.contains("category=logs"));
    }

    @Test
    void fetchActionLogs_WithNullFilterValues_ShouldUseEmptyFilters() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithoutFilters();
        int offset = 0;
        int limit = 10;

        Map<String, Object> responseBody = createSuccessfulResponseBody();
        ResponseEntity<Map<String, Object>> responseEntity = new ResponseEntity<>(responseBody, HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(responseEntity);

        // When
        CommonAdaptorResp<FilterListViewBatch> result = messageLogAdaptor.fetchActionLogs(report, offset, limit);

        // Then
        assertNotNull(result);
        assertTrue(result.isSuccess());

        // Verify no filters were added to URL (only pagination params)
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class));

        String capturedUrl = urlCaptor.getValue();
        assertTrue(capturedUrl.contains("page=1"));
        assertTrue(capturedUrl.contains("page_size=10"));
        assertFalse(capturedUrl.contains("status="));
        assertFalse(capturedUrl.contains("category="));
    }

    @Test
    void fetchActionLogs_WithFiltersHavingNullValues_ShouldSkipNullFilters() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithFilters();
        int offset = 0;
        int limit = 10;

        List<FilterValue> filters = Arrays.asList(
                new FilterValue("status", "active",null),
                new FilterValue("category", null,null), // null value should be skipped
                new FilterValue("type", "info",null)
        );
        when(objectMapper.readValue(anyString(), any(TypeReference.class))).thenReturn(filters);

        Map<String, Object> responseBody = createSuccessfulResponseBody();
        ResponseEntity<Map<String, Object>> responseEntity = new ResponseEntity<>(responseBody, HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(responseEntity);

        // When
        CommonAdaptorResp<FilterListViewBatch> result = messageLogAdaptor.fetchActionLogs(report, offset, limit);

        // Then
        assertNotNull(result);
        assertTrue(result.isSuccess());

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class));

        String capturedUrl = urlCaptor.getValue();
        assertTrue(capturedUrl.contains("status=active"));
        assertTrue(capturedUrl.contains("type=info"));
        assertFalse(capturedUrl.contains("category=")); // null value should be excluded
    }

    @Test
    void fetchActionLogs_WithPaginationOffset_ShouldCalculateCorrectPage() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithoutFilters();
        int offset = 20; // Should result in page 3 with limit 10
        int limit = 10;

        Map<String, Object> responseBody = createSuccessfulResponseBody();
        ResponseEntity<Map<String, Object>> responseEntity = new ResponseEntity<>(responseBody, HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(responseEntity);

        // When
        messageLogAdaptor.fetchActionLogs(report, offset, limit);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class));

        String capturedUrl = urlCaptor.getValue();
        assertTrue(capturedUrl.contains("page=3")); // offset 20 / limit 10 + 1 = 3
    }

    @Test
    void fetchActionLogs_WithNullResponseBody_ShouldThrowRuntimeException() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithoutFilters();
        ResponseEntity<Map<String, Object>> responseEntity = new ResponseEntity<>(null, HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(responseEntity);

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> messageLogAdaptor.fetchActionLogs(report, 0, 10));

        assertTrue(exception.getMessage().contains("Failed to fetch action logs"));
    }

    @Test
    void fetchActionLogs_WithSuccessFalseResponse_ShouldThrowRuntimeException() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithoutFilters();
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("success", false);
        responseBody.put("message", "API Error");

        ResponseEntity<Map<String, Object>> responseEntity = new ResponseEntity<>(responseBody, HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(responseEntity);

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> messageLogAdaptor.fetchActionLogs(report, 0, 10));

        assertTrue(exception.getMessage().contains("Failed to fetch action logs"));
    }

    @Test
    void fetchActionLogs_WithMissingDataInResponse_ShouldUseDefaults() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithoutFilters();
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("success", true);
        responseBody.put("message", "Success");

        Map<String, Object> data = new HashMap<>();
        data.put("logs", Arrays.asList(
                Map.of("id", 1, "message", "log1")
        ));
        // Missing page, page_size, total_records
        responseBody.put("data", data);

        ResponseEntity<Map<String, Object>> responseEntity = new ResponseEntity<>(responseBody, HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(responseEntity);

        // When
        CommonAdaptorResp<FilterListViewBatch> result = messageLogAdaptor.fetchActionLogs(report, 0, 10);

        // Then
        assertNotNull(result);
        assertTrue(result.isSuccess());

        FilterListViewBatch batch = result.getData();
        assertEquals(1, batch.getPage()); // default
        assertEquals(10, batch.getPageSize()); // default from limit
        assertEquals(1, batch.getTotalRecords()); // default to table size
    }

    @Test
    void fetchActionLogs_WithNullLogsInResponse_ShouldUseEmptyList() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithoutFilters();
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("success", true);
        responseBody.put("message", "Success");

        Map<String, Object> data = new HashMap<>();
        data.put("logs", null); // null logs
        data.put("page", 1);
        data.put("page_size", 10);
        data.put("total_records", 0);
        responseBody.put("data", data);

        ResponseEntity<Map<String, Object>> responseEntity = new ResponseEntity<>(responseBody, HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(responseEntity);

        // When
        CommonAdaptorResp<FilterListViewBatch> result = messageLogAdaptor.fetchActionLogs(report, 0, 10);

        // Then
        assertNotNull(result);
        assertTrue(result.isSuccess());

        FilterListViewBatch batch = result.getData();
        assertTrue(batch.getTableData().isEmpty());
        assertEquals(0, batch.getTotalRecords());
    }

    @Test
    void fetchActionLogs_WithHttpClientErrorException_ShouldThrowRuntimeException() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithoutFilters();
        HttpClientErrorException exception = new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request", "Error body".getBytes(), null);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenThrow(exception);

        // When & Then
        RuntimeException runtimeException = assertThrows(RuntimeException.class,
                () -> messageLogAdaptor.fetchActionLogs(report, 0, 10));

        assertTrue(runtimeException.getMessage().contains("User Provisioning service error: 400 BAD_REQUEST"));
        assertEquals(exception, runtimeException.getCause());
    }

    @Test
    void fetchActionLogs_WithHttpServerErrorException_ShouldThrowRuntimeException() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithoutFilters();
        HttpServerErrorException exception = new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Server Error", "Server error body".getBytes(), null);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenThrow(exception);

        // When & Then
        RuntimeException runtimeException = assertThrows(RuntimeException.class,
                () -> messageLogAdaptor.fetchActionLogs(report, 0, 10));

        assertTrue(runtimeException.getMessage().contains("User Provisioning service error: 500 INTERNAL_SERVER_ERROR"));
        assertEquals(exception, runtimeException.getCause());
    }

    @Test
    void fetchActionLogs_WithRestClientException_ShouldThrowRuntimeException() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithoutFilters();
        ResourceAccessException exception = new ResourceAccessException("Connection timeout");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenThrow(exception);

        // When & Then
        RuntimeException runtimeException = assertThrows(RuntimeException.class,
                () -> messageLogAdaptor.fetchActionLogs(report, 0, 10));

        assertTrue(runtimeException.getMessage().contains("Unable to connect to User Provisioning service"));
        assertEquals(exception, runtimeException.getCause());
    }

    @Test
    void fetchActionLogs_WithJSONParsingException_ShouldThrowRuntimeException() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithFilters();
        IOException ioException = new JsonProcessingException("Invalid JSON") {};

        when(objectMapper.readValue(anyString(), any(TypeReference.class))).thenThrow(ioException);

        // When & Then
        RuntimeException runtimeException = assertThrows(RuntimeException.class,
                () -> messageLogAdaptor.fetchActionLogs(report, 0, 10));

        assertTrue(runtimeException.getMessage().contains("Invalid filter values JSON"));
        assertEquals(ioException, runtimeException.getCause());
    }

    @Test
    void fetchActionLogs_WithGenericException_ShouldThrowRuntimeException() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithoutFilters();
        RuntimeException originalException = new RuntimeException("Unexpected error");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenThrow(originalException);

        // When & Then
        RuntimeException runtimeException = assertThrows(RuntimeException.class,
                () -> messageLogAdaptor.fetchActionLogs(report, 0, 10));

        assertTrue(runtimeException.getMessage().contains("Failed to fetch action logs"));
        assertEquals(originalException, runtimeException.getCause());
    }

    @Test
    void fetchActionLogs_WithEmptyFilters_ShouldProcessSuccessfully() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithFilters();
        List<FilterValue> emptyFilters = Collections.emptyList();
        when(objectMapper.readValue(anyString(), any(TypeReference.class))).thenReturn(emptyFilters);

        Map<String, Object> responseBody = createSuccessfulResponseBody();
        ResponseEntity<Map<String, Object>> responseEntity = new ResponseEntity<>(responseBody, HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(responseEntity);

        // When
        CommonAdaptorResp<FilterListViewBatch> result = messageLogAdaptor.fetchActionLogs(report, 0, 10);

        // Then
        assertNotNull(result);
        assertTrue(result.isSuccess());

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class));

        String capturedUrl = urlCaptor.getValue();
        // Should only contain pagination params, no filter params
        assertTrue(capturedUrl.contains("page=1"));
        assertTrue(capturedUrl.contains("page_size=10"));
        long filterParamCount = capturedUrl.chars().filter(ch -> ch == '=').count();
        assertEquals(2, filterParamCount); // Only page and page_size
    }

    // Helper methods
    private DownloadReport createDownloadReportWithFilters() {
        DownloadReport report = new DownloadReport();
        report.setFilterValuesJson("[{\"columnName\":\"status\",\"value\":\"active\"},{\"columnName\":\"category\",\"value\":\"logs\"}]");
        return report;
    }

    private DownloadReport createDownloadReportWithoutFilters() {
        DownloadReport report = new DownloadReport();
        report.setFilterValuesJson(null);
        return report;
    }

    private List<FilterValue> createFilterValues() {
        List<FilterValue> filters = new ArrayList<>();
        filters.add(new FilterValue("status", "active",null));
        filters.add(new FilterValue("category", "logs",null));
        return filters;
    }

    private Map<String, Object> createSuccessfulResponseBody() {
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("success", true);
        responseBody.put("message", "Success");

        Map<String, Object> data = new HashMap<>();
        data.put("page", 1);
        data.put("page_size", 10);
        data.put("total_records", 2);
        data.put("logs", Arrays.asList(
                Map.of("id", 1, "message", "log1", "status", "active"),
                Map.of("id", 2, "message", "log2", "status", "inactive")
        ));

        responseBody.put("data", data);
        return responseBody;
    }
}

