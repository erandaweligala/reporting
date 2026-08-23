package com.axonect.ee.enterpriseintegration.application.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning and mapping knobs for the USER_DATA_DUMP report.
 *
 * <p>The defaults are sized for the ~3 million row nightly dump: a JDBC fetch size large enough
 * that the Oracle round trips disappear next to the scan itself, an Elasticsearch page that keeps
 * one aggregation response comfortably inside a few MB, and a CSV buffer large enough that the
 * writer issues on the order of one write syscall per thousand rows.
 */
@Component
@ConfigurationProperties(prefix = "report.user-dump")
@Data
public class UserDumpProperties {

    /** Rows Oracle ships per round trip. Every row is text, so a large fetch stays cheap. */
    private int jdbcFetchSize = 5000;

    /** Seconds the dump query may run before Oracle cancels it. */
    private int queryTimeoutSeconds = 7200;

    /** Character buffer, in bytes, held in front of the CSV file. */
    private int csvBufferBytes = 1 << 20;

    /**
     * Number of username ranges scanned in parallel. Each shard is an independent Oracle cursor
     * plus an independent Elasticsearch aggregation, writing its own part file; the parts are
     * concatenated in shard order at the end. 1 disables sharding and writes straight to the
     * output file.
     */
    private int shards = 4;

    /** How many days back the dump reports on. 1 is the previous day (D-1). */
    private int daysBack = 1;

    /**
     * Zone the reported day is resolved in. It has to be the zone the CDR daily indices are named
     * in, otherwise a dump run near midnight aggregates the neighbouring day's index.
     */
    private String timezone = java.util.TimeZone.getDefault().getID();

    /** Threads available to shard workers. Defaults to one per shard when left at 0. */
    private int workerThreads = 0;

    /** Oracle format model applied to every timestamp column of the dump. */
    private String dateFormat = "DD/MM/YYYY HH24:MI:SS";

    /** BUCKET_INSTANCE.BUCKET_TYPE that carries the plan bandwidth. */
    private String bandwidthBucketType = "BANDWIDTH";

    /** BUCKET_INSTANCE.BUCKET_TYPE that carries the data quota. */
    private String quotaBucketType = "DATA";

    /** Written to QUOTA when the quota bucket is flagged unlimited. */
    private String unlimitedQuotaLabel = "Unlimited";

    /** Usage lookup against Elasticsearch. */
    private Usage usage = new Usage();

    @Data
    public static class Usage {

        /** Base name of the CDR daily indices, matching the cdr-service `sessions-data` setting. */
        private String index = "radius-sessions";

        /** Composite aggregation page size: usernames returned per Elasticsearch round trip. */
        private int pageSize = 2000;

        /** Maximum distinct buckets counted for a single user in a day. */
        private int bucketsPerUser = 20;

        /**
         * Whether sessionInstances is mapped as a nested type. When it is, usage can be summed
         * per bucket; when it is not, Elasticsearch flattens the array and a per-bucket sum would
         * silently attribute every instance's usage to every bucket the session touched, so the
         * whole session's usage is reported against the user instead.
         */
        private boolean nested = true;

        /** Path of the session instance array inside the CDR session document. */
        private String instancesPath = "sessionInstances";

        /** Set false to emit an empty UTLIZED_QUOTA instead of querying Elasticsearch. */
        private boolean enabled = true;
    }
}
