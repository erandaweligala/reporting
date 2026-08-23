package com.axonect.ee.enterpriseintegration.domain.client;

import com.axonect.ee.enterpriseintegration.application.transport.request.FilterValue;
import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.application.util.StringUtil;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.exception.ReportClientException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class SubscriberDetailsAdaptor {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${subscriber-details.base-url}")
    private String subscriberDetailsBaseUrl;

    public SubscriberDetailsAdaptor(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @SuppressWarnings("unchecked")
    public CommonAdaptorResp<FilterListViewBatch> fetchSubscriberDetails(DownloadReport report, int offset, int limit) {
        long start = System.currentTimeMillis();
        log.info("Fetching subscriber details for report: {} , {}", report.getReportType(), report.getId());

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
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(subscriberDetailsBaseUrl)
                    .queryParam("page", offset / limit + 1)
                    .queryParam("page_size", limit);

            for (FilterValue filter : filters) {
                if (filter.getValue() != null ) {
                    uriBuilder.queryParam(StringUtil.toSnakeCase(filter.getColumnName()), filter.getValue());
                }
            }

            String url = uriBuilder.toUriString();
            log.info("Calling subscriber details API: {}", url);

            // Make the GET request
            HttpHeaders headers = new HttpHeaders();
            headers.set("channel", "SRH");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<>() {}
            );

            Map<String, Object> body = response.getBody();
            if (body == null || !(Boolean.TRUE.equals(body.get("success")))) {
                throw new ReportClientException("Failed to fetch subscriber details: " + body,null);
            }

            // Extract data
            Map<String, Object> data = (Map<String, Object>) body.get("data");
            List<Map<String, Object>> users = (List<Map<String, Object>>) data.get("users");

            // Prepare FilterListViewBatch
            FilterListViewBatch batch = new FilterListViewBatch();
            batch.setTableData(users != null ? users : Collections.emptyList());
            batch.setPage((Integer) data.getOrDefault("page", 1));
            batch.setPageSize((Integer) data.getOrDefault("page_size", limit));
            batch.setTotalRecords((Integer) data.getOrDefault("total_records", batch.getTableData().size()));


            // Wrap in CommonAdaptorResp
            CommonAdaptorResp<FilterListViewBatch> adaptorResp = new CommonAdaptorResp<>();
            adaptorResp.setSuccess(true);
            adaptorResp.setMessage((String) body.get("message"));
            adaptorResp.setData(batch);

            log.info("Successfully fetched subscriber details in {} ms", System.currentTimeMillis() - start);
            return adaptorResp;

        } catch (HttpStatusCodeException ex) {
            // 4xx / 5xx from server
            log.error("User Provisioning service returned HTTP {} with body: {}",
                    ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
            throw new ReportClientException(
                    "User Provisioning service error: " + ex.getStatusCode(), ex);
        }
        catch (RestClientException ex) {
            // Connection issues, timeouts, etc.
            log.error("Error calling User Provisioning service", ex);
            throw new ReportClientException(
                    "Unable to connect to User Provisioning service", ex);
        }
        catch (IOException ex) {
            // JSON parsing issues
            log.error("Failed to parse filter values JSON", ex);
            throw new ReportClientException(
                    "Invalid filter values JSON", ex);
        }
        catch (Exception ex) {
            log.error("Unexpected error while fetching subscriber details", ex);
            throw new ReportClientException(
                    "Failed to fetch subscriber details", ex);
        }
    }

}
