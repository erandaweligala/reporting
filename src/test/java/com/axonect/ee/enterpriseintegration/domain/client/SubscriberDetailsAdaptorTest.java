package com.axonect.ee.enterpriseintegration.domain.client;

import com.axonect.ee.enterpriseintegration.application.transport.request.FilterValue;
import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.exception.ReportClientException;
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
import org.springframework.http.HttpEntity;
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
class SubscriberDetailsAdaptorTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private ObjectMapper objectMapper;

    private SubscriberDetailsAdaptor subscriberDetailsAdaptor;

    private final String baseUrl = "http://test-subscriber-details.com";

    @BeforeEach
    void setUp() {
        subscriberDetailsAdaptor = new SubscriberDetailsAdaptor(restTemplate);
        ReflectionTestUtils.setField(subscriberDetailsAdaptor, "subscriberDetailsBaseUrl", baseUrl);
        ReflectionTestUtils.setField(subscriberDetailsAdaptor, "objectMapper", objectMapper);
    }

    @Test
    void constructor_ShouldInitializeWithRestTemplate() {
        RestTemplate template = mock(RestTemplate.class);
        SubscriberDetailsAdaptor adaptor = new SubscriberDetailsAdaptor(template);
        assertNotNull(adaptor);
    }

    @Test
    void fetchSubscriberDetails_WithValidRequest_ShouldReturnSuccessResponse() throws Exception {
        // Given
        DownloadReport report = createReportWithFilters();
        int offset = 0;
        int limit = 10;

        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(createFilterValues());

        ResponseEntity<Map<String, Object>> response =
                new ResponseEntity<>(createSuccessResponseBody(), HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                .thenReturn(response);

        // When
        CommonAdaptorResp<FilterListViewBatch> result =
                subscriberDetailsAdaptor.fetchSubscriberDetails(report, offset, limit);

        // Then
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("Success", result.getMessage());

        FilterListViewBatch batch = result.getData();
        assertNotNull(batch);
        assertEquals(2, batch.getTableData().size());
        assertEquals(1, batch.getPage());
        assertEquals(10, batch.getPageSize());
        assertEquals(25, batch.getTotalRecords());

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);

        verify(restTemplate).exchange(
                urlCaptor.capture(),
                eq(HttpMethod.GET),
                entityCaptor.capture(),
                any(ParameterizedTypeReference.class)
        );

        String url = urlCaptor.getValue();
        assertTrue(url.contains("page=1"));
        assertTrue(url.contains("page_size=10"));
        assertTrue(url.contains("msisdn=94770000000"));
        assertTrue(url.contains("status=ACTIVE"));

        // Verify channel header
        assertEquals("SRH", entityCaptor.getValue().getHeaders().getFirst("channel"));
    }

    @Test
    void fetchSubscriberDetails_WithNullFilters_ShouldOnlyUsePaginationParams() {
        // Given
        DownloadReport report = createReportWithoutFilters();

        ResponseEntity<Map<String, Object>> response =
                new ResponseEntity<>(createSuccessResponseBody(), HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                .thenReturn(response);

        // When
        CommonAdaptorResp<FilterListViewBatch> result =
                subscriberDetailsAdaptor.fetchSubscriberDetails(report, 0, 10);

        // Then
        assertTrue(result.isSuccess());

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(
                urlCaptor.capture(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)
        );

        String url = urlCaptor.getValue();
        assertTrue(url.contains("page=1"));
        assertTrue(url.contains("page_size=10"));
        assertFalse(url.contains("msisdn="));
    }

    @Test
    void fetchSubscriberDetails_WithNullFilterValue_ShouldSkipThatFilter() throws Exception {
        // Given
        DownloadReport report = createReportWithFilters();

        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Arrays.asList(
                        new FilterValue("msisdn", "94770000000", null),
                        new FilterValue("email", null, null)
                ));

        ResponseEntity<Map<String, Object>> response =
                new ResponseEntity<>(createSuccessResponseBody(), HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                .thenReturn(response);

        // When
        subscriberDetailsAdaptor.fetchSubscriberDetails(report, 0, 10);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(
                urlCaptor.capture(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)
        );

        String url = urlCaptor.getValue();
        assertTrue(url.contains("msisdn=94770000000"));
        assertFalse(url.contains("email="));
    }

    @Test
    void fetchSubscriberDetails_WithOffset_ShouldCalculateCorrectPage() {
        // Given
        DownloadReport report = createReportWithoutFilters();

        ResponseEntity<Map<String, Object>> response =
                new ResponseEntity<>(createSuccessResponseBody(), HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                .thenReturn(response);

        // When
        subscriberDetailsAdaptor.fetchSubscriberDetails(report, 20, 10);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(
                urlCaptor.capture(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)
        );

        assertTrue(urlCaptor.getValue().contains("page=3"));
    }

    @Test
    void fetchSubscriberDetails_WithNullResponseBody_ShouldThrowException() {
        // Given
        DownloadReport report = createReportWithoutFilters();

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        // When & Then
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> subscriberDetailsAdaptor.fetchSubscriberDetails(report, 0, 10));

        assertTrue(ex.getMessage().contains("Failed to fetch subscriber details"));
    }

    @Test
    void fetchSubscriberDetails_WithSuccessFalse_ShouldThrowException() {
        // Given
        DownloadReport report = createReportWithoutFilters();

        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", "Failure");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        // When & Then
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> subscriberDetailsAdaptor.fetchSubscriberDetails(report, 0, 10));

        assertTrue(ex.getMessage().contains("Failed to fetch subscriber details"));
    }

    @Test
    void fetchSubscriberDetails_WithNullUsers_ShouldReturnEmptyList() {
        // Given
        DownloadReport report = createReportWithoutFilters();

        Map<String, Object> body = createSuccessResponseBody();
        ((Map<String, Object>) body.get("data")).put("users", null);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        // When
        CommonAdaptorResp<FilterListViewBatch> result =
                subscriberDetailsAdaptor.fetchSubscriberDetails(report, 0, 10);

        // Then
        assertTrue(result.isSuccess());
        assertTrue(result.getData().getTableData().isEmpty());
    }

    @Test
    void fetchSubscriberDetails_WithHttpClientError_ShouldThrowRuntimeException() {
        // Given
        DownloadReport report = createReportWithoutFilters();

        HttpClientErrorException ex = new HttpClientErrorException(HttpStatus.BAD_REQUEST);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                .thenThrow(ex);

        // When & Then
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> subscriberDetailsAdaptor.fetchSubscriberDetails(report, 0, 10));

        assertTrue(thrown.getMessage().contains("User Provisioning service error"));
        assertEquals(ex, thrown.getCause());
    }

    @Test
    void fetchSubscriberDetails_WithHttpServerError_ShouldThrowRuntimeException() {
        // Given
        DownloadReport report = createReportWithoutFilters();

        HttpServerErrorException ex = new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                .thenThrow(ex);

        // When & Then
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> subscriberDetailsAdaptor.fetchSubscriberDetails(report, 0, 10));

        assertTrue(thrown.getMessage().contains("User Provisioning service error"));
        assertEquals(ex, thrown.getCause());
    }

    @Test
    void fetchSubscriberDetails_WithRestClientException_ShouldThrowRuntimeException() {
        // Given
        DownloadReport report = createReportWithoutFilters();

        ResourceAccessException ex = new ResourceAccessException("Timeout");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                .thenThrow(ex);

        // When & Then
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> subscriberDetailsAdaptor.fetchSubscriberDetails(report, 0, 10));

        assertTrue(thrown.getMessage().contains("Unable to connect to User Provisioning service"));
        assertEquals(ex, thrown.getCause());
    }

    @Test
    void fetchSubscriberDetails_WithInvalidJSON_ShouldThrowRuntimeException() throws Exception {
        // Given
        DownloadReport report = createReportWithFilters();

        IOException ex = new JsonProcessingException("Invalid JSON") {};

        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenThrow(ex);

        // When & Then
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> subscriberDetailsAdaptor.fetchSubscriberDetails(report, 0, 10));

        assertTrue(thrown.getMessage().contains("Invalid filter values JSON"));
        assertEquals(ex, thrown.getCause());
    }

    @Test
    void fetchSubscriberDetails_ShouldAlwaysSendChannelHeaderAsSRH() {
        // Given
        DownloadReport report = createReportWithoutFilters();

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                .thenReturn(new ResponseEntity<>(createSuccessResponseBody(), HttpStatus.OK));

        // When
        subscriberDetailsAdaptor.fetchSubscriberDetails(report, 0, 10);

        // Then
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                anyString(),
                eq(HttpMethod.GET),
                entityCaptor.capture(),
                any(ParameterizedTypeReference.class)
        );

        assertEquals("SRH", entityCaptor.getValue().getHeaders().getFirst("channel"));
    }

    /* ---------------- helper methods ---------------- */

    private DownloadReport createReportWithFilters() {
        DownloadReport report = new DownloadReport();
        report.setFilterValuesJson(
                "[{\"columnName\":\"msisdn\",\"value\":\"94770000000\"}," +
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
                new FilterValue("msisdn", "94770000000", null),
                new FilterValue("status", "ACTIVE", null)
        );
    }

    private Map<String, Object> createSuccessResponseBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Success");

        Map<String, Object> data = new HashMap<>();
        data.put("page", 1);
        data.put("page_size", 10);
        data.put("total_records", 25);
        data.put("users", Arrays.asList(
                Map.of("id", 1, "name", "User 1"),
                Map.of("id", 2, "name", "User 2")
        ));

        body.put("data", data);
        return body;
    }
}