package com.axonect.ee.enterpriseintegration.domain.client;

import com.axonect.ee.enterpriseintegration.application.transport.response.CommonAdaptorResp;
import com.axonect.ee.enterpriseintegration.application.transport.response.FilterListViewBatch;
import com.axonect.ee.enterpriseintegration.domain.entity.AuditLogFilterValue;
import com.axonect.ee.enterpriseintegration.domain.entity.AuditLogRequest;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.exception.ReportClientException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
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
public class AuditLogAdaptor {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${audit-log.base-url}")
    private String auditLogBaseUrl;

    public AuditLogAdaptor(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @SuppressWarnings("unchecked")
    public CommonAdaptorResp<FilterListViewBatch> fetchAuditLogs(DownloadReport report, int offset, int limit) {
        long start = System.currentTimeMillis();
        log.info("Fetching audit logs for report: {} , {}", report.getReportType(), report.getId());

        try {
            // Deserialize filterValuesJson to List<FilterValue>
            List<AuditLogFilterValue> filters = new ArrayList<>();
            if (report.getFilterValuesJson() != null) {
                filters = objectMapper.readValue(
                        report.getFilterValuesJson(),
                        new TypeReference<List<AuditLogFilterValue>>() {
                        }
                );
            }

            AuditLogRequest request = new AuditLogRequest(
                  filters,
                  offset,
                  limit
            );

            // Build query params
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(auditLogBaseUrl);
            String url = uriBuilder.toUriString();
            log.info("Calling audit logs API: {}", url);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<AuditLogRequest> requestEntity =
                    new HttpEntity<>(request, headers);

            // Make the POST request
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    requestEntity,
                    new ParameterizedTypeReference<>() {}
            );

            Map<String, Object> body = response.getBody();
            if (body == null) {
                throw new ReportClientException("Empty response from audit log service", null);
            }

            // Extract result block
            Map<String, Object> result = (Map<String, Object>) body.get("result");
            if (result == null) {
                throw new ReportClientException("Missing result section in response: " + body, null);
            }

            // Validate resultCode
            String resultCode = (String) result.get("resultCode");
            if (!"00".equalsIgnoreCase(resultCode)) {
                throw new ReportClientException(
                        "Failed to fetch audit logs: " + result.get("resultDescription"),
                        null
                );
            }

            // Extract page details (inside result)
            Map<String, Object> pageDetails =
                    (Map<String, Object>) result.get("pageDetail");

            // Extract response data
            List<Map<String, Object>> logs =
                    (List<Map<String, Object>>) body.get("responseData");

            // Prepare FilterListViewBatch
            FilterListViewBatch batch = new FilterListViewBatch();
            batch.setTableData(logs != null ? logs : Collections.emptyList());

            if (pageDetails != null) {
                batch.setPage(((Number) pageDetails.getOrDefault("pageNumber", 1)).intValue());
                batch.setPageSize(((Number) pageDetails.getOrDefault("pageElementCount", limit)).intValue());
                batch.setTotalRecords(((Number) pageDetails
                        .getOrDefault("totalRecords", batch.getTableData().size()))
                        .intValue());
            } else {
                batch.setPage(1);
                batch.setPageSize(limit);
                batch.setTotalRecords(batch.getTableData().size());
            }

            // Wrap in CommonAdaptorResp
            CommonAdaptorResp<FilterListViewBatch> adaptorResp = new CommonAdaptorResp<>();
            adaptorResp.setSuccess(true);
            adaptorResp.setMessage((String) result.get("resultDescription"));
            adaptorResp.setData(batch);

            log.info("Successfully fetched audit logs in {} ms",
                    System.currentTimeMillis() - start);

            return adaptorResp;

        } catch (HttpStatusCodeException ex) {
            // 4xx / 5xx from server
            log.error("Secure Request Handler returned HTTP {} with body: {}",
                    ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
            throw new ReportClientException(
                    "Secure Request Handler error: " + ex.getStatusCode(), ex);
        }
        catch (RestClientException ex) {
            // Connection issues, timeouts, etc.
            log.error("Error calling Secure Request Handler", ex);
            throw new ReportClientException(
                    "Unable to connect to Secure Request Handler", ex);
        }
        catch (IOException ex) {
            // JSON parsing issues
            log.error("Failed to parse filter values JSON", ex);
            throw new ReportClientException(
                    "Invalid filter values JSON", ex);
        }
        catch (Exception ex) {
            log.error("Unexpected error while fetching audit logs", ex);
            throw new ReportClientException(
                    "Failed to fetch audit logs", ex);
        }
    }
}
