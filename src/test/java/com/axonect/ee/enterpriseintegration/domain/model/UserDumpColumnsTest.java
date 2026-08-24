package com.axonect.ee.enterpriseintegration.domain.model;

import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDumpColumnsTest {

    /**
     * The header of the dump as its consumers already parse it, copied verbatim from the sample
     * file the format was agreed against. Consumers read the file positionally, so this is a
     * contract: if a change makes this assertion fail, the change breaks them.
     */
    private static final String EXPECTED_HEADER =
            "USER_ID,GROUP_BANDWIDTH,BILLING,BILLING_ACCOUNT_REF,CIRCUIT_ID,CONCURRENCY,"
                    + "CONTACT_EMAIL,CONTACT_NAME,CONTACT_NUMBER,CREATED_DATE,CUSTOM_TIMEOUT,"
                    + "CYCLE_DATE,ENCRYPTION_METHOD,GROUP_ID,IDLE_TIMEOUT,IP_ALLOCATION,"
                    + "IP_POOL_NAME,IPV4,IPV6,MAC_ADDRESS,NAS_PORT_TYPE,ORIGINAL_MAC_ADDRESS,"
                    + "REMOTE_ID,REQUEST_ID,SESSION_TIMEOUT,STATUS,SUBSCRIPTION,UPDATED_DATE,"
                    + "SLMN,VLAN_ID,NAS_IP_ADDRESS,NOTIFICATION_TEMPLATES,"
                    + "CUSTOMER_ACTIVATION_DATE,BUNDLE_ACTIVATION_DATE,BUNDLE_NAME,"
                    + "PLAN_BANDWIDTH,QUOTA,UTLIZED_QUOTA,BUNDLE_DEACTIVATION_DATE";

    @Test
    void headerMatchesTheAgreedContractExactly() {
        String header = UserDumpColumns.columns().stream()
                .map(CsvColumn::getLabel)
                .collect(Collectors.joining(","));

        assertEquals(EXPECTED_HEADER, header);
    }

    @Test
    void hasThirtyNineColumns() {
        assertEquals(39, UserDumpColumns.COLUMN_COUNT);
        assertEquals(39, UserDumpColumns.columns().size());
    }

    @Test
    void indexConstantsPointAtTheColumnsTheyName() {
        List<CsvColumn> columns = UserDumpColumns.columns();
        assertEquals("USER_ID", columns.get(UserDumpColumns.USER_ID).getLabel());
        assertEquals("PLAN_BANDWIDTH", columns.get(UserDumpColumns.PLAN_BANDWIDTH).getLabel());
        assertEquals("UTLIZED_QUOTA", columns.get(UserDumpColumns.UTILIZED_QUOTA).getLabel());
    }

    @Test
    void columnKeysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (CsvColumn column : UserDumpColumns.columns()) {
            assertTrue(keys.add(column.getKey()), "duplicate column key: " + column.getKey());
        }
    }
}
