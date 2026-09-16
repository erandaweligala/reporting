package com.axonect.ee.enterpriseintegration.domain.client;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.FieldCapsRequest;
import co.elastic.clients.elasticsearch.core.FieldCapsResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.field_caps.FieldCapability;
import co.elastic.clients.json.JsonpDeserializer;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.axonect.ee.enterpriseintegration.application.config.UserDumpProperties;
import com.axonect.ee.enterpriseintegration.domain.exception.ReportClientException;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    void offersBothSpellingsOfTheNasFieldTheTwoMappingsProduce() {
        // A text mapping puts the aggregatable address in a .keyword sub-field; a keyword or an ip
        // mapping puts it in the field itself. Neither raises when aggregated as the other \u2014 the
        // terms aggregation simply answers with nothing \u2014 so both are worth asking about.
        assertEquals(List.of("nasIpAddress.keyword", "nasIpAddress"),
                UsageAggregationClient.nasIpFieldCandidates("nasIpAddress.keyword"),
                "the configured spelling first, then the same name without the sub-field");
        assertEquals(List.of("nasIpAddress", "nasIpAddress.keyword"),
                UsageAggregationClient.nasIpFieldCandidates("nasIpAddress"),
                "and the sub-field added for a name configured without one");
        assertEquals(List.of(), UsageAggregationClient.nasIpFieldCandidates(""),
                "a field configured as nothing is no field to ask about");
    }

    @Test
    void readsTheNasAddressFromTheFieldTheReportedDaysMappingCanAggregate() throws Exception {
        // The bug this exists for: cdr-service maps nasIpAddress as a plain keyword, so
        // nasIpAddress.keyword is not a field of the index at all. Aggregating it returns no terms
        // and no error, which the dump wrote out as an empty NAS_IP_ADDRESS for every user of
        // every run \u2014 next to a UTLIZED_QUOTA that was right, because the usage join reads fields
        // the mapping does have.
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        when(clientProvider.getIfAvailable()).thenReturn(elasticsearch);
        when(elasticsearch.fieldCaps(any(FieldCapsRequest.class)))
                .thenReturn(mappingWith("nasIpAddress", true));

        String request = firstRequestOf(elasticsearch, LocalDate.of(2026, 8, 22));

        assertTrue(request.contains("\"field\":\"nasIpAddress\""),
                "the terms aggregation names the field the mapping can aggregate");
        assertFalse(request.contains("nasIpAddress.keyword"),
                "and not the sub-field spelling, which this index does not carry");
    }

    @Test
    void keepsTheConfiguredSpellingWhereTheMappingCarriesIt() throws Exception {
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        when(clientProvider.getIfAvailable()).thenReturn(elasticsearch);
        when(elasticsearch.fieldCaps(any(FieldCapsRequest.class)))
                .thenReturn(mappingWith("nasIpAddress.keyword", true));

        assertTrue(firstRequestOf(elasticsearch, LocalDate.of(2026, 8, 22))
                        .contains("\"field\":\"nasIpAddress.keyword\""),
                "a text mapping's sub-field is what the default already asks for");
    }

    @Test
    void asksTheReportedDaysOwnIndexWhichSpellingsItHas() throws Exception {
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        when(clientProvider.getIfAvailable()).thenReturn(elasticsearch);
        when(elasticsearch.fieldCaps(any(FieldCapsRequest.class)))
                .thenReturn(mappingWith("nasIpAddress", true));

        client.open(LocalDate.of(2026, 8, 22), null, null);

        ArgumentCaptor<FieldCapsRequest> captured = ArgumentCaptor.forClass(FieldCapsRequest.class);
        verify(elasticsearch).fieldCaps(captured.capture());
        FieldCapsRequest asked = captured.getValue();

        assertEquals(List.of("radius-sessions-2026.08.22"), asked.index(),
                "the NAS address is read from that index, so that index is the one whose mapping "
                        + "decides the field \u2014 not the whole scan, which spans older mappings");
        assertEquals(List.of("nasIpAddress.keyword", "nasIpAddress"), asked.fields());
        assertEquals(Boolean.TRUE, asked.ignoreUnavailable(),
                "a day with no index of its own is an empty column, not a failed dump");
    }

    @Test
    void aFieldTheMappingCannotAggregateLeavesTheRestOfTheDumpAlone() throws Exception {
        // Neither spelling is there. The column cannot be filled, but every other column can, so
        // the run goes on with the configured field and the log carries the diagnosis.
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        when(clientProvider.getIfAvailable()).thenReturn(elasticsearch);
        when(elasticsearch.fieldCaps(any(FieldCapsRequest.class)))
                .thenReturn(mappingWith("somethingElse", true));

        assertTrue(firstRequestOf(elasticsearch, LocalDate.of(2026, 8, 22))
                        .contains("\"field\":\"nasIpAddress.keyword\""),
                "the configured spelling stands when the cluster names no better one");
    }

    @Test
    void anUnaggregatableFieldIsNotTakenForAnAggregatableOne() throws Exception {
        // nasIpAddress exists under a text mapping too \u2014 analysed, and so not something a terms
        // aggregation can read. The sub-field beside it is the one that can.
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        when(clientProvider.getIfAvailable()).thenReturn(elasticsearch);
        when(elasticsearch.fieldCaps(any(FieldCapsRequest.class)))
                .thenReturn(mappingWith("nasIpAddress", false));

        assertTrue(firstRequestOf(elasticsearch, LocalDate.of(2026, 8, 22))
                        .contains("\"field\":\"nasIpAddress.keyword\""),
                "a field the mapping has but cannot aggregate is no answer");
    }

    @Test
    void aClusterThatWillNotAnswerTheLookupDoesNotFailTheDump() throws Exception {
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        when(clientProvider.getIfAvailable()).thenReturn(elasticsearch);
        when(elasticsearch.fieldCaps(any(FieldCapsRequest.class)))
                .thenThrow(new IOException("field capabilities refused"));

        assertTrue(firstRequestOf(elasticsearch, LocalDate.of(2026, 8, 22))
                        .contains("\"field\":\"nasIpAddress.keyword\""),
                "the configured spelling is tried, exactly as it was before the lookup existed");
    }

    @Test
    void theShardsOfOneRunResolveTheFieldBetweenThemRatherThanEachPayingForIt() throws Exception {
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        when(clientProvider.getIfAvailable()).thenReturn(elasticsearch);
        when(elasticsearch.fieldCaps(any(FieldCapsRequest.class)))
                .thenReturn(mappingWith("nasIpAddress", true));
        LocalDate day = LocalDate.of(2026, 8, 22);

        client.open(day, null, "g");
        client.open(day, "g", "s");
        client.open(day, "s", null);

        verify(elasticsearch).fieldCaps(any(FieldCapsRequest.class));
    }

    @Test
    void theBucketExtractDoesNotPayForAFieldItHasNoColumnFor() throws Exception {
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        when(clientProvider.getIfAvailable()).thenReturn(elasticsearch);

        client.openBucketTotals(null, null);

        verify(elasticsearch, never()).fieldCaps(any(FieldCapsRequest.class));
    }

    @Test
    void readsTheNasAddressOfTheLatestSessionWhereTheReportedDaysIndexHoldsNone() throws Exception {
        // The bug this exists for: a session document is filed under the day its session started,
        // so the reported day's index is not "that day's sessions". A line that came up this
        // morning is in today's index and one that came up last week and has not dropped since is
        // in last week's — neither is in D-1's, so the filter found no address while UTLIZED_QUOTA
        // beside it reported that very session's usage out of the same scan.
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        when(clientProvider.getIfAvailable()).thenReturn(elasticsearch);
        when(elasticsearch.fieldCaps(any(FieldCapsRequest.class)))
                .thenReturn(mappingWith("nasIpAddress.keyword", true));

        String request = firstRequestOf(elasticsearch, LocalDate.of(2026, 9, 15));

        assertTrue(request.contains("\"latest_day\""),
                "the newest index the user appears in is read beside the reported day's");
        assertTrue(request.contains("\"field\":\"_index\""),
                "which is a terms aggregation on the index each session document landed in");
        assertTrue(request.contains("\"order\":[{\"_key\":\"desc\"}]"),
                "ordered by name, since the daily names sort by date — the newest index, not the "
                        + "busiest, which is the many-year NAS the reported day's filter exists "
                        + "to keep out of the column");
    }

    @Test
    void fillsTheColumnFromTheLatestSessionForAUserTheReportedDayHasNoSessionOf() throws Exception {
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        when(clientProvider.getIfAvailable()).thenReturn(elasticsearch);
        when(elasticsearch.fieldCaps(any(FieldCapsRequest.class)))
                .thenReturn(mappingWith("nasIpAddress.keyword", true));
        when(elasticsearch.search(any(SearchRequest.class), any(Class.class)))
                .thenReturn(responseOf(aUserAnchoredOn(null, "192.168.239.85")));

        UserUsageCursor.UserUsage usage =
                client.open(LocalDate.of(2026, 9, 15), null, null).forUser("taiwowilliams");

        assertEquals("192.168.239.85", usage.nasIpAddress(),
                "the address the cluster holds for them, rather than the empty column a row "
                        + "reporting their usage was written with");
        assertTrue(usage.nasIpFromAnotherDay(),
                "and the dump is told it is not the reported day's, so the shard can count it");
        assertEquals(80L, usage.total(), "the usage beside it is unchanged");
    }

    @Test
    void theReportedDaysOwnAddressStillWinsWhereThatDayHasOne() throws Exception {
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        when(clientProvider.getIfAvailable()).thenReturn(elasticsearch);
        when(elasticsearch.fieldCaps(any(FieldCapsRequest.class)))
                .thenReturn(mappingWith("nasIpAddress.keyword", true));
        when(elasticsearch.search(any(SearchRequest.class), any(Class.class)))
                .thenReturn(responseOf(aUserAnchoredOn("10.20.30.40", "192.168.239.85")));

        UserUsageCursor.UserUsage usage =
                client.open(LocalDate.of(2026, 9, 15), null, null).forUser("taiwowilliams");

        assertEquals("10.20.30.40", usage.nasIpAddress(),
                "NAS_IP_ADDRESS is the reported day's wherever that day names one; the fallback "
                        + "fills the column, it does not take it over");
        assertFalse(usage.nasIpFromAnotherDay());
    }

    @Test
    void aUserWithNoSessionAnywhereInTheScanStillReportsNoAddressAtAll() throws Exception {
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        when(clientProvider.getIfAvailable()).thenReturn(elasticsearch);
        when(elasticsearch.fieldCaps(any(FieldCapsRequest.class)))
                .thenReturn(mappingWith("nasIpAddress.keyword", true));
        when(elasticsearch.search(any(SearchRequest.class), any(Class.class)))
                .thenReturn(responseOf(aUserAnchoredOn(null, null)));

        UserUsageCursor.UserUsage usage =
                client.open(LocalDate.of(2026, 9, 15), null, null).forUser("taiwowilliams");

        assertNull(usage.nasIpAddress(),
                "a subscriber whose sessions named no NAS has no address to fall back to either");
        assertFalse(usage.nasIpFromAnotherDay(),
                "an empty column is not a column filled from another day");
    }

    @Test
    void theReportedDayCanBeMadeTheOnlySourceAgain() throws Exception {
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        when(clientProvider.getIfAvailable()).thenReturn(elasticsearch);
        when(elasticsearch.fieldCaps(any(FieldCapsRequest.class)))
                .thenReturn(mappingWith("nasIpAddress.keyword", true));
        properties.getUsage().setNasIpFallbackToLatestDay(false);

        String request = firstRequestOf(elasticsearch, LocalDate.of(2026, 9, 15));

        assertTrue(request.contains("\"reported_day\""),
                "the reported day's filter is what the column is read through either way");
        assertFalse(request.contains("\"latest_day\""),
                "and nothing beside it is asked of the cluster when the fallback is off");
    }

    @Test
    void theBucketExtractPaysForNeitherDaysAggregation() throws Exception {
        // It has no reported day and no NAS column, so both the filter and the fallback beside it
        // are left out of the request rather than run per user across the whole keyspace.
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        when(clientProvider.getIfAvailable()).thenReturn(elasticsearch);
        when(elasticsearch.search(any(SearchRequest.class), any(Class.class)))
                .thenThrow(new IOException("no cluster behind this test"));

        assertThrows(ReportClientException.class,
                () -> client.openBucketTotals(null, null).forUser("taiwowilliams"));

        ArgumentCaptor<SearchRequest> captured = ArgumentCaptor.forClass(SearchRequest.class);
        verify(elasticsearch).search(captured.capture(), any(Class.class));
        String request = asJson(captured.getValue());

        assertFalse(request.contains("\"reported_day\""));
        assertFalse(request.contains("\"latest_day\""));
    }

    /** A field capabilities answer from one index that maps {@code field} and nothing else. */
    private FieldCapsResponse mappingWith(String field, boolean aggregatable) {
        return FieldCapsResponse.of(r -> r
                .indices("radius-sessions-2026.08.22")
                .fields(field, Map.of("keyword", FieldCapability.of(c -> c
                        .type("keyword")
                        .searchable(true)
                        .aggregatable(aggregatable)))));
    }

    /**
     * The first search the cursor sends, as JSON. The request is built when the cursor is first
     * advanced, so the aggregation it carries is the one a run actually asks the cluster for.
     */
    @SuppressWarnings("unchecked")
    private String firstRequestOf(ElasticsearchClient elasticsearch, LocalDate day) throws Exception {
        when(elasticsearch.search(any(SearchRequest.class), any(Class.class)))
                .thenThrow(new IOException("no cluster behind this test"));

        assertThrows(ReportClientException.class,
                () -> client.open(day, null, null).forUser("taiwowilliams"));

        ArgumentCaptor<SearchRequest> captured = ArgumentCaptor.forClass(SearchRequest.class);
        verify(elasticsearch).search(captured.capture(), any(Class.class));

        return asJson(captured.getValue());
    }

    /** A search request as it goes over the wire. */
    private String asJson(SearchRequest request) {
        JsonpMapper mapper = new JacksonJsonpMapper();
        StringWriter writer = new StringWriter();
        try (JsonGenerator generator = mapper.jsonProvider().createGenerator(writer)) {
            mapper.serialize(request, generator);
        }
        return writer.toString();
    }

    /**
     * One composite bucket for {@code taiwowilliams}, who drew 80 bytes on the bundle's quota
     * bucket: {@code reportedDay} is the address the reported day's index names for them and
     * {@code latestDay} the one the newest index they appear in does, either of them null for a
     * day that names none.
     *
     * <p>Written as the cluster answers it, typed keys and all, because it is the answer's shape
     * that the fallback turns on: the filter comes back with no terms for a user whose session
     * document was filed under another day, which is exactly what a user with no NAS at all looks
     * like under it.
     */
    private String aUserAnchoredOn(String reportedDay, String latestDay) {
        return """
                {
                  "took": 7,
                  "timed_out": false,
                  "_shards": {"total": 2, "successful": 2, "skipped": 0, "failed": 0},
                  "hits": {"total": {"value": 0, "relation": "eq"}, "hits": []},
                  "aggregations": {
                    "composite#by_user": {
                      "after_key": {"user": "taiwowilliams"},
                      "buckets": [{
                        "key": {"user": "taiwowilliams"},
                        "doc_count": 2,
                        "filter#reported_day": {"doc_count": %d, "sterms#nas_ip": %s},
                        "sterms#latest_day": {
                          "doc_count_error_upper_bound": 0,
                          "sum_other_doc_count": 0,
                          "buckets": [{
                            "key": "radius-sessions-2026.09.16",
                            "doc_count": 2,
                            "sterms#nas_ip": %s
                          }]
                        },
                        "nested#instances": {
                          "doc_count": 2,
                          "sterms#by_bucket": {
                            "doc_count_error_upper_bound": 0,
                            "sum_other_doc_count": 0,
                            "buckets": [{
                              "key": "4444913775",
                              "doc_count": 2,
                              "sum#usage": {"value": 80.0},
                              "sterms#by_service": {
                                "doc_count_error_upper_bound": 0,
                                "sum_other_doc_count": 0,
                                "buckets": [{
                                  "key": "4444827954",
                                  "doc_count": 2,
                                  "sum#usage": {"value": 80.0}
                                }]
                              }
                            }]
                          }
                        }
                      }]
                    }
                  }
                }
                """.formatted(reportedDay == null ? 0 : 2, addresses(reportedDay), addresses(latestDay));
    }

    /** A terms aggregate naming one address, or the empty one a day with no sessions answers with. */
    private String addresses(String nasIpAddress) {
        String buckets = nasIpAddress == null
                ? ""
                : "{\"key\": \"" + nasIpAddress + "\", \"doc_count\": 2}";
        return "{\"doc_count_error_upper_bound\": 0, \"sum_other_doc_count\": 0, "
                + "\"buckets\": [" + buckets + "]}";
    }

    /** A search response as the client deserializes one off the wire. */
    private SearchResponse<Void> responseOf(String json) {
        JsonpMapper mapper = new JacksonJsonpMapper();
        try (JsonParser parser = mapper.jsonProvider().createParser(new StringReader(json))) {
            return SearchResponse.createSearchResponseDeserializer(
                    JsonpDeserializer.<Void>fixedValue(null)).deserialize(parser, mapper);
        }
    }
}
