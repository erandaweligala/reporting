package com.axonect.ee.enterpriseintegration.domain.client;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import com.axonect.ee.enterpriseintegration.application.config.UserDumpProperties;
import com.axonect.ee.enterpriseintegration.domain.exception.ReportClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsageAggregationClientTest {

    private ObjectProvider<ElasticsearchClient> clientProvider;
    private UserDumpProperties properties;
    private UsageAggregationClient client;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        clientProvider = mock(ObjectProvider.class);
        properties = new UserDumpProperties();
        client = new UsageAggregationClient(clientProvider, properties);
    }

    @Test
    void readsTheSingleDailyIndexTheCdrServiceWroteThatDayTo() {
        assertEquals("radius-sessions-2026.08.22", client.indexFor(LocalDate.of(2026, 8, 22)));
    }

    @Test
    void followsTheConfiguredIndexBaseName() {
        properties.getUsage().setIndex("radius-sessions-dr");

        assertEquals("radius-sessions-dr-2026.01.05", client.indexFor(LocalDate.of(2026, 1, 5)));
    }

    @Test
    void turningTheLookupOffLeavesBothElasticsearchColumnsEmptyRatherThanFailing() {
        properties.getUsage().setEnabled(false);

        UserUsageCursor cursor = client.open(LocalDate.of(2026, 8, 22), null, null);

        assertNull(cursor.forUser("taiwowilliams"),
                "with no cursor there is neither a UTLIZED_QUOTA nor a NAS_IP_ADDRESS to report");
    }

    @Test
    void sumsEveryIndexUpToTheReportedDayRatherThanThatDayAlone() {
        // UTLIZED_QUOTA is read next to QUOTA, the bucket's whole allowance, so the usage beside
        // it has to be everything drawn from the bucket — not the slice of it that fell on D-1.
        properties.setTimezone("UTC");
        LocalDate yesterday = LocalDate.now(ZoneId.of("UTC")).minusDays(1);

        List<String> indices = client.indicesUpTo(yesterday);

        assertEquals("radius-sessions-*", indices.get(0),
                "every daily index the cluster still holds is in scope");
        assertTrue(indices.contains("-" + client.indexFor(yesterday.plusDays(1))),
                "the index cdr-service is writing into now is struck out of the wildcard by name");
        assertFalse(indices.contains("-" + client.indexFor(yesterday)),
                "the reported day is the last one included, not an excluded one");
    }

    @Test
    void aBoundedLookbackNamesItsOwnDaysInsteadOfTheWildcard() {
        properties.getUsage().setLookbackDays(3);

        assertEquals(List.of("radius-sessions-2026.08.20",
                        "radius-sessions-2026.08.21",
                        "radius-sessions-2026.08.22"),
                client.indicesUpTo(LocalDate.of(2026, 8, 22)),
                "oldest first, ending at the reported day");
    }

    @Test
    void readsTheNasAddressBesideTheUsageInTheSameAggregation() {
        assertTrue(UsageAggregationClient.aggregationNames().contains("nas_ip"),
                "NAS_IP_ADDRESS must be read in the pass that already walks every user");
        assertEquals(5, properties.getUsage().getNasAddressesPerUser(),
                "a user who moved between NASes is still resolved to the one most of their "
                        + "sessions used, without a per-user fetch");
        assertEquals("nasIpAddress.keyword", properties.getUsage().getNasIpField(),
                "the field cdr-service records the address under on the session document");
        assertTrue(UsageAggregationClient.aggregationNames().contains("reported_day"),
                "the usage around it now sums every index, so the address is read inside a "
                        + "filter that keeps NAS_IP_ADDRESS the reported day's");
    }

    @Test
    void splitsEachBucketsTotalByTheBundleThatDrewIt() {
        assertTrue(UsageAggregationClient.aggregationNames().contains("by_service"),
                "a bucket id names the plan's bucket, so the service instance is what makes the "
                        + "total this bundle's rather than every bundle's");
        assertTrue(properties.getUsage().isScopeToService(),
                "on by default: without it a recurring subscriber's every cycle would be "
                        + "reported against the current cycle's QUOTA");
        assertEquals(0, properties.getUsage().getLookbackDays(),
                "0 is every index the cluster holds, which is what a lifetime total means");
    }

    @Test
    void leavesTheWrappedCountersAlreadyInTheIndicesOutOfTheTotal() {
        // 4294967286 is 2^32 - 10: a 10 byte counter regression the CDR wrapped on the way in.
        // Summed as volume it is 4.29 GB the subscriber never drew, and the indices written
        // before cdr-service stopped producing them still hold theirs.
        long window = properties.getUsage().getWrapWindow();
        Query filter = UsageAggregationClient.drawnUsage("sessionInstances.usage", window);

        List<Query> excluded = filter.bool().mustNot();
        assertEquals(2, excluded.size(), "the wrapped band, and anything outright negative");

        RangeQuery wrapped = excluded.get(0).range();
        assertEquals("sessionInstances.usage", wrapped.field());
        assertEquals(UsageAggregationClient.counterWrap() - window, wrapped.gt().to(Long.class),
                "the band starts one window below the roll-over");
        assertEquals(UsageAggregationClient.counterWrap(), wrapped.lt().to(Long.class),
                "and stops at it, so the reported 4294967286 falls inside");
        assertTrue(4294967286L > wrapped.gt().to(Long.class)
                        && 4294967286L < wrapped.lt().to(Long.class),
                "the value from the report is excluded by this band");

        assertEquals(0L, excluded.get(1).range().lt().to(Long.class),
                "a negative usage would subtract from a total it was never part of");
    }

    @Test
    void stillSumsEveryByteOfASessionThatGenuinelyMovedMoreThanFourGigabytes() {
        Query filter = UsageAggregationClient.drawnUsage("sessionInstances.usage",
                properties.getUsage().getWrapWindow());
        RangeQuery wrapped = filter.bool().mustNot().get(0).range();

        // Above the roll-over is not a wrap — nothing subtracted from 2^32 can land there.
        assertTrue(12_000_000_000L > wrapped.lt().to(Long.class),
                "12 GB is past the band's upper edge, so the filter keeps it");
        assertTrue(3_000_000_000L < wrapped.gt().to(Long.class),
                "and 3 GB in one accounting event is below its lower edge");
    }

    @Test
    void theWrapWindowCanBeTurnedOffOnceNoIndexPredatesTheFix() {
        Query filter = UsageAggregationClient.drawnUsage("sessionInstances.usage", 0L);

        assertTrue(filter.isMatchAll(),
                "with the window at 0 the dump sums the indices exactly as they stand");
    }

    @Test
    void sumsTheUsageInsideTheFilterRatherThanBesideIt() {
        assertTrue(UsageAggregationClient.aggregationNames().contains("drawn"),
                "the bucket totals sit under the filter, so a wrapped instance is out of every "
                        + "figure the dump reports rather than only out of one of them");
        assertEquals(1L << 30, properties.getUsage().getWrapWindow(),
                "1 GiB by default: a regression of more than that in one accounting event is "
                        + "less likely than a subscriber moving 3.22 GB between two of them");
    }

    @Test
    void failsLoudlyWhenTheLookupIsOnButNoClusterIsWired() {
        when(clientProvider.getIfAvailable()).thenReturn(null);

        ReportClientException thrown = assertThrows(ReportClientException.class,
                () -> client.open(LocalDate.of(2026, 8, 22), null, null));

        assertSame(null, thrown.getCause());
    }
}
