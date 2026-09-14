package com.axonect.ee.enterpriseintegration.domain.client;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.axonect.ee.enterpriseintegration.application.config.UserDumpProperties;
import com.axonect.ee.enterpriseintegration.domain.exception.ReportClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void sumsEveryIndexTheClusterHoldsRatherThanStoppingAtTheReportedDay() {
        // UTLIZED_QUOTA is read next to QUOTA, the bucket's whole allowance, so the usage beside
        // it has to be everything drawn from the bucket — not the slice of it that fell on D-1,
        // and not everything up to D-1 either. Striking the later days out of the wildcard is
        // what reported a subscriber whose sessions all started today as having no usage at all.
        properties.setTimezone("UTC");
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));

        List<String> indices = client.indicesThroughToday();

        assertEquals(List.of("radius-sessions-*"), indices,
                "every daily index the cluster still holds is in scope, today's included");
        assertFalse(indices.contains("-" + client.indexFor(today)),
                "the index cdr-service is writing into carries this morning's usage, so the "
                        + "total has to read it rather than strike it out by name");
    }

    @Test
    void aBoundedLookbackNamesItsOwnDaysInsteadOfTheWildcard() {
        properties.setTimezone("UTC");
        properties.getUsage().setLookbackDays(3);
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));

        assertEquals(List.of(client.indexFor(today.minusDays(2)),
                        client.indexFor(today.minusDays(1)),
                        client.indexFor(today)),
                client.indicesThroughToday(),
                "oldest first, ending today rather than at the reported day");
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
                "the usage around it sums every index the cluster holds, so the address is read "
                        + "inside a filter that keeps NAS_IP_ADDRESS the reported day's");
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
                "0 is every index the cluster holds, which is what a running total means");
    }

    @Test
    void theBucketExtractReadsTheSameTotalsWithoutTheNasAggregationBesideThem() {
        // BUCKET_INSTANCE.USAGE is the figure UTLIZED_QUOTA is, summed over the same indices. What
        // it does not have is a reported day — so the filter and the terms aggregation that read
        // NAS_IP_ADDRESS for the dump are left out of the request rather than run per user and
        // dropped on the floor.
        when(clientProvider.getIfAvailable()).thenReturn(mock(ElasticsearchClient.class));

        assertNotNull(client.openBucketTotals(null, null));
        assertEquals(List.of("radius-sessions-*"), client.indicesThroughToday(),
                "the same scan as the dump's: every daily index the cluster holds");
    }

    @Test
    void turningTheLookupOffLeavesTheBucketExtractsUsageEmptyRatherThanFailing() {
        properties.getUsage().setEnabled(false);

        assertNull(client.openBucketTotals(null, null).forUser("taiwowilliams"));
    }

    @Test
    void theBucketTotalsCursorAlsoFailsLoudlyWhenNoClusterIsWired() {
        when(clientProvider.getIfAvailable()).thenReturn(null);

        assertThrows(ReportClientException.class, () -> client.openBucketTotals(null, null));
    }

    @Test
    void failsLoudlyWhenTheLookupIsOnButNoClusterIsWired() {
        when(clientProvider.getIfAvailable()).thenReturn(null);

        ReportClientException thrown = assertThrows(ReportClientException.class,
                () -> client.open(LocalDate.of(2026, 8, 22), null, null));

        assertSame(null, thrown.getCause());
    }

    @Test
    void aggregatesOnTheFieldItselfWhereTheClusterCan() {
        // An index template that declares sessionInstances nested normally maps its sub-fields as
        // plain keywords, and a keyword is what a terms aggregation reads directly.
        assertEquals("sessionInstances.bucketId",
                UsageAggregationClient.spellingOf("sessionInstances.bucketId", null,
                        Set.of("sessionInstances.bucketId")));
    }

    @Test
    void fallsBackToTheKeywordSubFieldOfATextMapping() {
        assertEquals("sessionInstances.bucketId.keyword",
                UsageAggregationClient.spellingOf("sessionInstances.bucketId", null,
                        Set.of("sessionInstances.bucketId.keyword")));
    }

    @Test
    void aMappingThatAnswersNothingLeavesTheSpellingTheLookupHasAlwaysAssumed() {
        // A cluster that will not answer the question is not a reason to fail a dump, and the
        // sub-field is what every run before this one asked for.
        assertEquals("userName.keyword",
                UsageAggregationClient.spellingOf("userName", null, Set.of()));
    }

    @Test
    void aFieldConfiguredByNameSkipsTheQuestionAltogether() {
        // The last resort for a mapping the probe cannot settle — the same lever nas-ip-field is.
        assertEquals("sessionInstances.bucket",
                UsageAggregationClient.spellingOf("sessionInstances.bucketId", "sessionInstances.bucket",
                        Set.of("sessionInstances.bucketId")));
    }

    @Test
    void theCdrKeyFieldsAreResolvedRatherThanPinnedByDefault() {
        assertNull(properties.getUsage().getBucketIdField(),
                "asking the cluster which spelling it can aggregate on is the default; a terms "
                        + "aggregation on a field the mapping does not have returns no terms "
                        + "rather than an error, which is a column of zeroes and no way to see it");
        assertNull(properties.getUsage().getServiceIdField());
        assertNull(properties.getUsage().getUsernameField());
    }
}
