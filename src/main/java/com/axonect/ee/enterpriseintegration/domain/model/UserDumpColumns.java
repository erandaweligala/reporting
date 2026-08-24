package com.axonect.ee.enterpriseintegration.domain.model;

import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;

import java.util.List;

/**
 * The USER_DUMP column contract: the exact set, spelling and order of the columns the generated
 * file must carry. Consumers of the dump read it positionally, so this order is fixed — new
 * columns go at the end, and existing ones are never reordered or renamed.
 *
 * <p>{@code UTLIZED_QUOTA} is spelled as-is on purpose: that is the header the downstream
 * consumers already parse, and "correcting" it here would break them.
 *
 * <p>The index constants are the positions of the fields that are filled in after the database
 * row is read — the Elasticsearch-derived usage figure — and of the fields used to key that
 * lookup.
 */
public final class UserDumpColumns {

    public static final int USER_ID = 0;
    public static final int PLAN_BANDWIDTH = 35;
    public static final int UTILIZED_QUOTA = 37;

    private static final List<CsvColumn> COLUMNS = List.of(
            new CsvColumn("user_id", "USER_ID"),
            new CsvColumn("group_bandwidth", "GROUP_BANDWIDTH"),
            new CsvColumn("billing", "BILLING"),
            new CsvColumn("billing_account_ref", "BILLING_ACCOUNT_REF"),
            new CsvColumn("circuit_id", "CIRCUIT_ID"),
            new CsvColumn("concurrency", "CONCURRENCY"),
            new CsvColumn("contact_email", "CONTACT_EMAIL"),
            new CsvColumn("contact_name", "CONTACT_NAME"),
            new CsvColumn("contact_number", "CONTACT_NUMBER"),
            new CsvColumn("created_date", "CREATED_DATE"),
            new CsvColumn("custom_timeout", "CUSTOM_TIMEOUT"),
            new CsvColumn("cycle_date", "CYCLE_DATE"),
            new CsvColumn("encryption_method", "ENCRYPTION_METHOD"),
            new CsvColumn("group_id", "GROUP_ID"),
            new CsvColumn("idle_timeout", "IDLE_TIMEOUT"),
            new CsvColumn("ip_allocation", "IP_ALLOCATION"),
            new CsvColumn("ip_pool_name", "IP_POOL_NAME"),
            new CsvColumn("ipv4", "IPV4"),
            new CsvColumn("ipv6", "IPV6"),
            new CsvColumn("mac_address", "MAC_ADDRESS"),
            new CsvColumn("nas_port_type", "NAS_PORT_TYPE"),
            new CsvColumn("original_mac_address", "ORIGINAL_MAC_ADDRESS"),
            new CsvColumn("remote_id", "REMOTE_ID"),
            new CsvColumn("request_id", "REQUEST_ID"),
            new CsvColumn("session_timeout", "SESSION_TIMEOUT"),
            new CsvColumn("status", "STATUS"),
            new CsvColumn("subscription", "SUBSCRIPTION"),
            new CsvColumn("updated_date", "UPDATED_DATE"),
            new CsvColumn("slmn", "SLMN"),
            new CsvColumn("vlan_id", "VLAN_ID"),
            new CsvColumn("nas_ip_address", "NAS_IP_ADDRESS"),
            new CsvColumn("notification_templates", "NOTIFICATION_TEMPLATES"),
            new CsvColumn("customer_activation_date", "CUSTOMER_ACTIVATION_DATE"),
            new CsvColumn("bundle_activation_date", "BUNDLE_ACTIVATION_DATE"),
            new CsvColumn("bundle_name", "BUNDLE_NAME"),
            new CsvColumn("plan_bandwidth", "PLAN_BANDWIDTH"),
            new CsvColumn("quota", "QUOTA"),
            new CsvColumn("utlized_quota", "UTLIZED_QUOTA"),
            new CsvColumn("bundle_deactivation_date", "BUNDLE_DEACTIVATION_DATE")
    );

    public static final int COLUMN_COUNT = COLUMNS.size();

    private UserDumpColumns() {
    }

    public static List<CsvColumn> columns() {
        return COLUMNS;
    }
}
