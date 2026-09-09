package com.axonect.ee.enterpriseintegration.domain.client;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.axonect.ee.enterpriseintegration.application.config.UserDumpProperties;
import com.axonect.ee.enterpriseintegration.domain.exception.ReportClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void readsTheNasAddressBesideTheUsageInTheSameAggregation() {
        assertTrue(UsageAggregationClient.aggregationNames().contains("nas_ip"),
                "NAS_IP_ADDRESS must be read in the pass that already walks every user");
        assertEquals(5, properties.getUsage().getNasAddressesPerUser(),
                "a user who moved between NASes is still resolved to the one most of their "
                        + "sessions used, without a per-user fetch");
        assertEquals("nasIpAddress.keyword", properties.getUsage().getNasIpField(),
                "the field cdr-service records the address under on the session document");
    }

    @Test
    void failsLoudlyWhenTheLookupIsOnButNoClusterIsWired() {
        when(clientProvider.getIfAvailable()).thenReturn(null);

        ReportClientException thrown = assertThrows(ReportClientException.class,
                () -> client.open(LocalDate.of(2026, 8, 22), null, null));

        assertSame(null, thrown.getCause());
    }
}
