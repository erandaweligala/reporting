package com.axonect.ee.enterpriseintegration.domain.client;

import com.axonect.ee.enterpriseintegration.application.transport.request.FilterValue;
import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.exception.ReportClientException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class ProductDetailsAdaptor {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${product-details.base-url}")
    private String productDetailsBaseUrl;

    public ProductDetailsAdaptor(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @SuppressWarnings("unchecked")
    public CommonAdaptorResp<FilterListViewBatch> fetchProductDetails(DownloadReport report, int offset, int limit) {
        long start = System.currentTimeMillis();
        log.info("Fetching product details for report: {}, {}", report.getReportType(), report.getId());

        try {
            // Deserialize filterValuesJson to List<FilterValue>
            List<FilterValue> filters = new ArrayList<>();
            if (report.getFilterValuesJson() != null) {
                filters = objectMapper.readValue(
                        report.getFilterValuesJson(),
                        new TypeReference<>() {
                        }
                );
            }

            // Build query params
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(productDetailsBaseUrl)
                    .queryParam("page", offset / limit + 1)
                    .queryParam("page_size", limit);

            for (FilterValue filter : filters) {
                if (filter.getValue() != null ) {
                    uriBuilder.queryParam(filter.getColumnName(), filter.getValue());
                }
            }

            URI uri = uriBuilder.build().toUri();
            log.info("Calling product details API: {}", uri);

            // Make the GET request
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<>() {}
            );

            Map<String, Object> body = response.getBody();
            if (body == null || !(Boolean.TRUE.equals(body.get("success")))) {
                throw new ReportClientException("Failed to fetch product details: " + body,null);
            }


            // Extract data
            Map<String, Object> pageDetails = (Map<String, Object>) body.get("pageDetails");
            Map<String, Object> data = (Map<String, Object>) body.get("data");
            List<Map<String, Object>> plans = (List<Map<String, Object>>) data.get("plans");

            // Prepare FilterListViewBatch
            FilterListViewBatch batch = new FilterListViewBatch();
            batch.setTableData(plans != null ? plans : Collections.emptyList());
            batch.setPage((Integer) pageDetails.getOrDefault("pageNumber", 1));
            batch.setPageSize((Integer) pageDetails.getOrDefault("pageElementCount", limit));
            batch.setTotalRecords((Integer) pageDetails.getOrDefault("totalRecords", batch.getTableData().size()));


            // Wrap in CommonAdaptorResp
            CommonAdaptorResp<FilterListViewBatch> adaptorResp = new CommonAdaptorResp<>();
            adaptorResp.setSuccess(true);
            adaptorResp.setMessage((String) body.get("message"));
            adaptorResp.setData(batch);

            log.info("Successfully fetched product details in {} ms", System.currentTimeMillis() - start);
            return adaptorResp;

        } catch (HttpStatusCodeException ex) {
            // 4xx / 5xx from server
            log.error("Product catalog service returned HTTP {} with body: {}",
                    ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
            throw new ReportClientException(
                    "Product catalog service error: " + ex.getStatusCode(), ex);
        }
        catch (RestClientException ex) {
            // Connection issues, timeouts, etc.
            log.error("Error calling Product catalog service", ex);
            throw new ReportClientException(
                    "Unable to connect to Product catalog service", ex);
        }
        catch (IOException ex) {
            // JSON parsing issues
            log.error("Failed to parse filter values JSON", ex);
            throw new ReportClientException(
                    "Invalid filter values JSON", ex);
        }
        catch (Exception ex) {
            log.error("Unexpected error while fetching product details", ex);
            throw new ReportClientException(
                    "Failed to fetch product details", ex);
        }
    }
}
