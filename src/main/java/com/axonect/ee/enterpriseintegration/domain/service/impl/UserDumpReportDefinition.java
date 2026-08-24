package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.application.transport.request.FilterValue;
import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import com.axonect.ee.enterpriseintegration.domain.client.SessionUsageAggregator;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.model.UserDumpChunk;
import com.axonect.ee.enterpriseintegration.domain.model.UserDumpColumns;
import com.axonect.ee.enterpriseintegration.domain.model.UserDumpRow;
import com.axonect.ee.enterpriseintegration.domain.repository.UserDumpRepository;
import com.axonect.ee.enterpriseintegration.domain.service.StreamingReportDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The full subscriber data dump, one row per user, for a single day (D-1 by default).
 *
 * <p>Each row is assembled from AAA_USER, with the user's MAC addresses from
 * AAA_USER_MAC_ADDRESS, their bundle from SERVICE_INSTANCE, that bundle's quota from
 * BUCKET_INSTANCE and its consumption from the day's Elasticsearch session index.
 *
 * <p>The dump is sized in millions of rows, so it is produced a page at a time and never held
 * whole: {@link UserDumpRepository} reads a keyset page of users with their database-side
 * enrichment already joined on, one {@link SessionUsageAggregator} request totals usage for
 * exactly the users on that page, the rows are handed straight to the sink, and the page is
 * dropped. Peak heap is therefore a function of the page size, not of the size of the dump.
 *
 * <p>Rows are emitted for every user in the snapshot, including users with no bundle, no bucket or
 * no sessions on the day; those simply carry empty bundle, quota or usage fields rather than being
 * dropped from the dump.
 */
@Component
@Slf4j
public class UserDumpReportDefinition implements StreamingReportDefinition {

    public static final String REPORT_TYPE = "USER_DUMP";

    /**
     * Optional filter, in the report's existing {@link FilterValue} filter list, naming the day to
     * dump as an ISO date. Absent — the normal scheduled case — the report covers D-1.
     */
    private static final String FILTER_REPORT_DATE = "reportdate";

    private final UserDumpRepository userDumpRepository;
    private final SessionUsageAggregator sessionUsageAggregator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Users per database page and per Elasticsearch aggregation request. Larger pages mean fewer
     * round trips to both but a proportionally larger live set; a few thousand keeps the terms
     * filter well inside Elasticsearch's default {@code index.max_terms_count} of 65536.
     */
    @Value("${report.user-dump.chunk-size:5000}")
    private int chunkSize;

    /**
     * Zone the reported day is defined in. Should match cdr-service's {@code app.timezone}, which
     * decides the date suffix of the session index this report reads.
     */
    @Value("${report.user-dump.timezone:UTC}")
    private String timezone;

    public UserDumpReportDefinition(UserDumpRepository userDumpRepository,
                                    SessionUsageAggregator sessionUsageAggregator) {
        this.userDumpRepository = userDumpRepository;
        this.sessionUsageAggregator = sessionUsageAggregator;
    }

    @Override
    public String reportType() {
        return REPORT_TYPE;
    }

    @Override
    public List<CsvColumn> columns() {
        return UserDumpColumns.columns();
    }

    @Override
    public long stream(DownloadReport report, RowSink sink) throws Exception {
        LocalDate day = resolveReportDay(report);
        LocalDateTime dayStart = day.atStartOfDay();
        LocalDateTime dayEnd = day.plusDays(1).atStartOfDay();

        log.info("Report ID {} | USER_DUMP for {} | chunk size {} | session index {}",
                report.getId(), day, chunkSize, sessionUsageAggregator.indexFor(day));

        String cursor = null;
        long totalRows = 0;
        int pageNo = 0;

        while (true) {
            UserDumpChunk chunk = userDumpRepository.fetchChunk(cursor, chunkSize, dayStart, dayEnd);
            if (chunk.isEmpty()) {
                break;
            }
            pageNo++;

            Map<String, Long> usage = sessionUsageAggregator.aggregateUsage(userNamesOf(chunk), day);

            for (UserDumpRow row : chunk.rows()) {
                Long utilized = SessionUsageAggregator.usageFor(usage, row.userName(), row.bucketId());
                row.values()[UserDumpColumns.UTILIZED_QUOTA] =
                        utilized == null ? null : Long.toString(utilized);
                sink.write(row.values());
            }

            totalRows += chunk.rows().size();
            log.info("Report ID {} | USER_DUMP | page {} | rows {} | total {}",
                    report.getId(), pageNo, chunk.rows().size(), totalRows);

            // A short page means the keyset scan has passed the last user.
            if (chunk.rows().size() < chunkSize) {
                break;
            }
            cursor = chunk.lastUserId();
        }

        log.info("Report ID {} | USER_DUMP complete for {} | {} row(s) over {} page(s)",
                report.getId(), day, totalRows, pageNo);
        return totalRows;
    }

    /**
     * The distinct user names on this page, which is what the usage aggregation is keyed on. Users
     * with no name are skipped; they cannot be matched to a session document.
     */
    private List<String> userNamesOf(UserDumpChunk chunk) {
        Set<String> names = new LinkedHashSet<>(chunk.rows().size());
        for (UserDumpRow row : chunk.rows()) {
            if (row.userName() != null && !row.userName().isEmpty()) {
                names.add(row.userName());
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * The day to dump: the {@code reportDate} filter when one was supplied — so a missed or
     * disputed run can be reproduced for a specific date — and otherwise yesterday.
     */
    LocalDate resolveReportDay(DownloadReport report) {
        LocalDate yesterday = LocalDate.now(ZoneId.of(timezone)).minusDays(1);

        String json = report.getFilterValuesJson();
        if (json == null || json.isBlank()) {
            return yesterday;
        }

        try {
            List<FilterValue> filters = objectMapper.readValue(json, new TypeReference<>() {
            });
            for (FilterValue filter : filters) {
                if (filter == null || filter.getColumnName() == null || filter.getValue() == null) {
                    continue;
                }
                if (FILTER_REPORT_DATE.equalsIgnoreCase(filter.getColumnName().replace("_", ""))) {
                    return LocalDate.parse(filter.getValue().toString().trim());
                }
            }
        } catch (DateTimeParseException e) {
            log.warn("Report ID {} | unparseable reportDate filter; falling back to {}",
                    report.getId(), yesterday, e);
        } catch (Exception e) {
            log.warn("Report ID {} | could not read filter values; falling back to {}",
                    report.getId(), yesterday, e);
        }
        return yesterday;
    }
}
