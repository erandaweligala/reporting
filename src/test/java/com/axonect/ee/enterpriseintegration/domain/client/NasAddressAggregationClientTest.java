package com.axonect.ee.enterpriseintegration.domain.client;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.axonect.ee.enterpriseintegration.application.config.UserDumpProperties;
import com.axonect.ee.enterpriseintegration.domain.exception.ReportClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NasAddressAggregationClientTest {

    private ObjectProvider<ElasticsearchClient> clientProvider;
    private UserDumpProperties properties;
    private NasAddressAggregationClient client;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        clientProvider = mock(ObjectProvider.class);
        properties = new UserDumpProperties();
        client = new NasAddressAggregationClient(clientProvider, properties);
    }

    @Test
    void readsTheSingleDailyIndexTheCdrServiceWroteThatDayTo() {
        assertEquals("radius-sessions-2026.08.22", client.indexFor(LocalDate.of(2026, 8, 22)));
    }

    @Test
    void followsTheConfiguredIndexBaseName() {
        properties.getNasLookup().setIndex("radius-sessions-dr");

        assertEquals("radius-sessions-dr-2026.01.05", client.indexFor(LocalDate.of(2026, 1, 5)));
    }

    @Test
    void turningTheLookupOffLeavesTheNasColumnEmptyRatherThanFailing() {
        properties.getNasLookup().setEnabled(false);

        UserNasAddressCursor cursor = client.open(LocalDate.of(2026, 8, 22), null, null);

        assertNull(cursor.forUser("taiwowilliams"), "with no cursor there is no address to report");
    }

    @Test
    void asksForNothingBeyondTheAddressItself() {
        // The aggregation used to carry the CDR usage deltas as well, nested and split per bucket
        // and per bundle, because UTLIZED_QUOTA was summed from them over every index the cluster
        // held. That column is BUCKET_INSTANCE.USAGE now, so all this reads is the address — one
        // terms aggregation per user, on doc values, over one day's index.
        assertEquals(List.of("by_user", "nas_ip"), NasAddressAggregationClient.aggregationNames());
        assertEquals(5, properties.getNasLookup().getNasAddressesPerUser(),
                "a user who moved between NASes is still resolved to the one most of their "
                        + "sessions used, without a per-user fetch");
        assertEquals("nasIpAddress.keyword", properties.getNasLookup().getNasIpField(),
                "the field cdr-service records the address under on the session document");
    }

    @Test
    void isOnUnlessADeploymentTurnsItOff() {
        assertTrue(new UserDumpProperties().getNasLookup().isEnabled());
        assertEquals("radius-sessions", new UserDumpProperties().getNasLookup().getIndex());
    }

    @Test
    void failsLoudlyWhenTheLookupIsOnButNoClusterIsWired() {
        when(clientProvider.getIfAvailable()).thenReturn(null);

        ReportClientException thrown = assertThrows(ReportClientException.class,
                () -> client.open(LocalDate.of(2026, 8, 22), null, null));

        assertSame(null, thrown.getCause());
    }
}
