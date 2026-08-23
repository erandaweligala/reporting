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
class ProductDetailsAdaptorTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private ObjectMapper objectMapper;

    private ProductDetailsAdaptor productDetailsAdaptor;

    private final String baseUrl = "http://test-product-details.com";

    @BeforeEach
    void setUp() {
        productDetailsAdaptor = new ProductDetailsAdaptor(restTemplate);
        ReflectionTestUtils.setField(productDetailsAdaptor, "productDetailsBaseUrl", baseUrl);
        ReflectionTestUtils.setField(productDetailsAdaptor, "objectMapper", objectMapper);
    }

    @Test
    void constructor_ShouldInitializeWithRestTemplate() {
        // Given
        RestTemplate testRestTemplate = mock(RestTemplate.class);

        // When
        ProductDetailsAdaptor adaptor = new ProductDetailsAdaptor(testRestTemplate);

        // Then
        assertNotNull(adaptor);
    }


    @Test
    void fetchProductDetails_WithNullResponseBody_ShouldThrowRuntimeException() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithoutFilters();
        ResponseEntity<Map<String, Object>> responseEntity = new ResponseEntity<>(null, HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(responseEntity);

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> productDetailsAdaptor.fetchProductDetails(report, 0, 10));

        assertTrue(exception.getMessage().contains("Failed to fetch product details"));
    }

    @Test
    void fetchProductDetails_WithSuccessFalseResponse_ShouldThrowRuntimeException() throws Exception {
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
                () -> productDetailsAdaptor.fetchProductDetails(report, 0, 10));

        assertTrue(exception.getMessage().contains("Failed to fetch product details"));
    }

    @Test
    void fetchProductDetails_WithMissingPageDetailsInResponse_ShouldUseDefaults() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithoutFilters();
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("success", true);
        responseBody.put("message", "Success");

        Map<String, Object> data = new HashMap<>();
        data.put("plans", Arrays.asList(
                Map.of("id", 1, "name", "plan1")
        ));
        responseBody.put("data", data);
        responseBody.put("pageDetails", null); // Missing pageDetails

        ResponseEntity<Map<String, Object>> responseEntity = new ResponseEntity<>(responseBody, HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(responseEntity);

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> productDetailsAdaptor.fetchProductDetails(report, 0, 10));

        // Should throw NPE when trying to access pageDetails.getOrDefault()
        assertTrue(exception.getMessage().contains("Failed to fetch product details"));
    }



    @Test
    void fetchProductDetails_WithMissingDataInResponse_ShouldThrowException() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithoutFilters();
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("success", true);
        responseBody.put("message", "Success");
        responseBody.put("pageDetails", new HashMap<>());
        // Missing "data" field

        ResponseEntity<Map<String, Object>> responseEntity = new ResponseEntity<>(responseBody, HttpStatus.OK);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class)))
                .thenReturn(responseEntity);

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> productDetailsAdaptor.fetchProductDetails(report, 0, 10));

        assertTrue(exception.getMessage().contains("Failed to fetch product details"));
    }


    @Test
    void fetchProductDetails_WithJSONParsingException_ShouldThrowRuntimeException() throws Exception {
        // Given
        DownloadReport report = createDownloadReportWithFilters();
        IOException ioException = new JsonProcessingException("Invalid JSON") {};

        when(objectMapper.readValue(anyString(), any(TypeReference.class))).thenThrow(ioException);

        // When & Then
        RuntimeException runtimeException = assertThrows(RuntimeException.class,
                () -> productDetailsAdaptor.fetchProductDetails(report, 0, 10));

        assertTrue(runtimeException.getMessage().contains("Invalid filter values JSON"));
        assertEquals(ioException, runtimeException.getCause());
    }


    // Helper methods
    private DownloadReport createDownloadReportWithFilters() {
        DownloadReport report = new DownloadReport();
        report.setFilterValuesJson("[{\"columnName\":\"category\",\"value\":\"electronics\"},{\"columnName\":\"status\",\"value\":\"active\"}]");
        return report;
    }

    private DownloadReport createDownloadReportWithoutFilters() {
        DownloadReport report = new DownloadReport();
        report.setFilterValuesJson(null);
        return report;
    }

    private List<FilterValue> createFilterValues() {
        List<FilterValue> filters = new ArrayList<>();
        filters.add(new FilterValue("category", "electronics",null));
        filters.add(new FilterValue("status", "active",null));
        return filters;
    }

    private Map<String, Object> createSuccessfulResponseBody() {
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("success", true);
        responseBody.put("message", "Success");

        // Product Details specific structure
        Map<String, Object> pageDetails = new HashMap<>();
        pageDetails.put("pageNumber", 1);
        pageDetails.put("pageElementCount", 10);
        pageDetails.put("totalRecords", 50);

        Map<String, Object> data = new HashMap<>();
        data.put("plans", Arrays.asList(
                Map.of("id", 1, "name", "Premium Plan", "category", "electronics"),
                Map.of("id", 2, "name", "Basic Plan", "category", "electronics")
        ));

        responseBody.put("pageDetails", pageDetails);
        responseBody.put("data", data);
        return responseBody;
    }
}
