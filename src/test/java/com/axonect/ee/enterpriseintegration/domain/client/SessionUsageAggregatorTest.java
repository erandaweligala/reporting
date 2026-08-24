package com.axonect.ee.enterpriseintegration.domain.client;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SessionUsageAggregatorTest {

    private SessionUsageAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new SessionUsageAggregator(mock(ElasticsearchClient.class));
        ReflectionTestUtils.setField(aggregator, "indexPrefix", "radius-sessions");
        ReflectionTestUtils.setField(aggregator, "compositePageSize", 5000);
    }

    /**
     * The daily suffix has to match the one cdr-service writes with, or the report would look in
     * an index that does not exist and report every user as having no usage.
     */
    @Test
    void indexNameMatchesTheDailyIndexCdrServiceWrites() {
        assertEquals("radius-sessions-2026.08.23", aggregator.indexFor(LocalDate.of(2026, 8, 23)));
        assertEquals("radius-sessions-2026.01.05", aggregator.indexFor(LocalDate.of(2026, 1, 5)));
    }

    @Test
    void lookupFindsUsageForAUserAndBucket() {
        Map<String, Long> usage = Map.of(
                SessionUsageAggregator.key("alice", "FTTH_50Mbps"), 70093948746L);

        assertEquals(70093948746L,
                SessionUsageAggregator.usageFor(usage, "alice", "FTTH_50Mbps"));
        assertNull(SessionUsageAggregator.usageFor(usage, "alice", "FTTH_100Mbps"));
        assertNull(SessionUsageAggregator.usageFor(usage, "bob", "FTTH_50Mbps"));
    }

    @Test
    void lookupIsNullSafeForUsersWithNoBundleOrBucket() {
        Map<String, Long> usage = Map.of(SessionUsageAggregator.key("alice", "B1"), 5L);
        assertNull(SessionUsageAggregator.usageFor(usage, "alice", null));
        assertNull(SessionUsageAggregator.usageFor(usage, null, "B1"));
        assertNull(SessionUsageAggregator.usageFor(usage, null, null));
    }

    /**
     * The two halves of the key are joined by a character neither can contain, so a user/bucket
     * pair cannot collide with a different pair whose halves concatenate to the same text.
     */
    @Test
    void keyHalvesCannotRunTogetherIntoAnotherPairsKey() {
        assertNotEquals(SessionUsageAggregator.key("a", "bc"),
                SessionUsageAggregator.key("ab", "c"));
        assertNotEquals(SessionUsageAggregator.key("a b", "c"),
                SessionUsageAggregator.key("a", "b c"));
    }

    @Test
    void noUsersMeansNoRequestAndNoUsage() {
        // A mock client would throw on any call; an empty page must not reach Elasticsearch.
        Map<String, Long> usage = aggregator.aggregateUsage(List.of(), LocalDate.of(2026, 8, 23));
        assertTrue(usage.isEmpty());
    }
}
