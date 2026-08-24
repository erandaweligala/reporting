package com.axonect.ee.enterpriseintegration.domain.service.impl;

import com.axonect.ee.enterpriseintegration.domain.client.SessionUsageAggregator;
import com.axonect.ee.enterpriseintegration.domain.entity.DownloadReport;
import com.axonect.ee.enterpriseintegration.domain.model.UserDumpChunk;
import com.axonect.ee.enterpriseintegration.domain.model.UserDumpColumns;
import com.axonect.ee.enterpriseintegration.domain.model.UserDumpRow;
import com.axonect.ee.enterpriseintegration.domain.repository.UserDumpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class UserDumpReportDefinitionTest {

    private static final int CHUNK = 2;

    private UserDumpRepository repository;
    private SessionUsageAggregator aggregator;
    private UserDumpReportDefinition definition;

    @BeforeEach
    void setUp() {
        repository = mock(UserDumpRepository.class);
        aggregator = mock(SessionUsageAggregator.class);
        definition = new UserDumpReportDefinition(repository, aggregator);
        ReflectionTestUtils.setField(definition, "chunkSize", CHUNK);
        ReflectionTestUtils.setField(definition, "timezone", "UTC");
    }

    private static UserDumpRow row(String userId, String userName, String bucketId) {
        String[] values = new String[UserDumpColumns.COLUMN_COUNT];
        values[UserDumpColumns.USER_ID] = userId;
        values[UserDumpColumns.PLAN_BANDWIDTH] = bucketId;
        return new UserDumpRow(values, userName, bucketId);
    }

    private static DownloadReport report(String filterJson) {
        DownloadReport report = new DownloadReport();
        report.setId(42L);
        report.setReportType(UserDumpReportDefinition.REPORT_TYPE);
        report.setFilterValuesJson(filterJson);
        return report;
    }

    @Test
    void defaultsToYesterday() {
        LocalDate expected = LocalDate.now(ZoneId.of("UTC")).minusDays(1);
        assertEquals(expected, definition.resolveReportDay(report(null)));
        assertEquals(expected, definition.resolveReportDay(report("  ")));
    }

    @Test
    void honoursAnExplicitReportDateFilter() {
        String json = "[{\"columnName\":\"reportDate\",\"value\":\"2026-08-01\",\"operation\":\"eq\"}]";
        assertEquals(LocalDate.of(2026, 8, 1), definition.resolveReportDay(report(json)));
    }

    @Test
    void acceptsTheFilterNameInSnakeCase() {
        String json = "[{\"columnName\":\"report_date\",\"value\":\"2026-07-15\",\"operation\":\"eq\"}]";
        assertEquals(LocalDate.of(2026, 7, 15), definition.resolveReportDay(report(json)));
    }

    @Test
    void fallsBackToYesterdayOnAnUnusableFilter() {
        LocalDate expected = LocalDate.now(ZoneId.of("UTC")).minusDays(1);
        assertEquals(expected, definition.resolveReportDay(
                report("[{\"columnName\":\"reportDate\",\"value\":\"not-a-date\"}]")));
        assertEquals(expected, definition.resolveReportDay(report("{ this is not json")));
    }

    @Test
    void streamsEveryPageUntilAShortPageEndsTheScan() throws Exception {
        LocalDate day = LocalDate.of(2026, 8, 23);
        String json = "[{\"columnName\":\"reportDate\",\"value\":\"2026-08-23\",\"operation\":\"eq\"}]";

        UserDumpChunk first = new UserDumpChunk(
                List.of(row("u1", "alice", "FTTH_50Mbps"), row("u2", "bob", "FTTH_50Mbps")), "u2");
        // A page shorter than the chunk size means the keyset scan has passed the last user.
        UserDumpChunk last = new UserDumpChunk(List.of(row("u3", "carol", "FTTH_100Mbps")), "u3");

        when(repository.fetchChunk(isNull(), eq(CHUNK), any(), any())).thenReturn(first);
        when(repository.fetchChunk(eq("u2"), eq(CHUNK), any(), any())).thenReturn(last);
        when(aggregator.aggregateUsage(any(), eq(day))).thenReturn(Map.of(
                SessionUsageAggregator.key("alice", "FTTH_50Mbps"), 70093948746L,
                SessionUsageAggregator.key("carol", "FTTH_100Mbps"), 12L));

        List<String[]> written = new ArrayList<>();
        long rows = definition.stream(report(json), values -> written.add(values.clone()));

        assertEquals(3, rows);
        assertEquals(3, written.size());
        assertEquals("70093948746", written.get(0)[UserDumpColumns.UTILIZED_QUOTA]);
        // bob had no sessions that day: the row is still emitted, with usage left empty.
        assertNull(written.get(1)[UserDumpColumns.UTILIZED_QUOTA]);
        assertEquals("12", written.get(2)[UserDumpColumns.UTILIZED_QUOTA]);

        verify(repository).fetchChunk(isNull(), eq(CHUNK), any(), any());
        verify(repository).fetchChunk(eq("u2"), eq(CHUNK), any(), any());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void aggregatesUsageOncePerPageForThatPagesUsersOnly() throws Exception {
        LocalDate day = LocalDate.of(2026, 8, 23);
        String json = "[{\"columnName\":\"reportDate\",\"value\":\"2026-08-23\",\"operation\":\"eq\"}]";

        when(repository.fetchChunk(isNull(), anyInt(), any(), any())).thenReturn(
                new UserDumpChunk(List.of(row("u1", "alice", "B1"), row("u2", "alice", "B2")), "u2"));
        when(repository.fetchChunk(eq("u2"), anyInt(), any(), any()))
                .thenReturn(new UserDumpChunk(List.of(), null));
        when(aggregator.aggregateUsage(any(), any())).thenReturn(Map.of());

        definition.stream(report(json), values -> {
        });

        // One aggregation per page, carrying that page's distinct user names.
        verify(aggregator).aggregateUsage(List.of("alice"), day);
    }

    @Test
    void queriesTheDayAsAHalfOpenRange() throws Exception {
        String json = "[{\"columnName\":\"reportDate\",\"value\":\"2026-08-23\",\"operation\":\"eq\"}]";
        when(repository.fetchChunk(any(), anyInt(), any(), any()))
                .thenReturn(new UserDumpChunk(List.of(), null));

        definition.stream(report(json), values -> {
        });

        verify(repository).fetchChunk(isNull(), eq(CHUNK),
                eq(LocalDateTime.of(2026, 8, 23, 0, 0)),
                eq(LocalDateTime.of(2026, 8, 24, 0, 0)));
    }

    @Test
    void reportsNoRowsWhenTheFirstPageIsEmpty() throws Exception {
        when(repository.fetchChunk(any(), anyInt(), any(), any()))
                .thenReturn(new UserDumpChunk(List.of(), null));

        List<String[]> written = new ArrayList<>();
        assertEquals(0, definition.stream(report(null), written::add));
        assertEquals(0, written.size());
    }
}
