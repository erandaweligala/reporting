package com.axonect.ee.enterpriseintegration.domain.client;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.axonect.ee.enterpriseintegration.application.config.UserDumpProperties;
import com.axonect.ee.enterpriseintegration.domain.exception.ReportClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates each user's CDR usage out of Elasticsearch, one username at a time.
 *
 * <p>The documents are the session documents cdr-service writes: one per session in a daily
 * {@code radius-sessions-yyyy.MM.dd} index, each carrying a {@code sessionInstances} array whose
 * entries hold a {@code usage} delta, the {@code bucketId} it was drawn from and the
 * {@code serviceId} of the bundle that owns that bucket. Each delta is the difference between the
 * {@code totalUsage} an accounting event reported and the one before it, so summing them is what
 * reconstructs a total — cdr-service stores no running total of its own.
 *
 * <p>What UTLIZED_QUOTA needs is that total, not a day of it. It is read next to QUOTA, which is
 * the bucket's whole allowance, so the usage beside it is summed over every daily index up to and
 * including the reported day rather than over the reported day alone. The index expression still
 * names the days it must not read — the index cdr-service is writing into now is excluded by name,
 * so a dump never touches the hot index however wide the rest of the scan is.
 *
 * <p>Usage is summed with a composite aggregation rather than fetched as hits: for 3 million users
 * the hits would be tens of millions of documents over the wire, whereas the aggregation ships one
 * number per user per bucket and pages deterministically through {@code after_key} with no scroll
 * context left open on the cluster.
 *
 * <p>The same pass also reports each user's NAS IP address, which AAA_USER does not carry: it is
 * the {@code nasIpAddress} cdr-service records on the session document from the CDR every
 * accounting event carries. That column is the reported day's, not the lifetime's, so it is read
 * inside a filter on the day's own index while the usage around it sums the lot. A user's sessions
 * can name more than one NAS across a day, so it is read as a small terms aggregation and the
 * most-used address wins — doc values only, no fetch phase, which is what keeps 3 million users
 * affordable next to a {@code top_hits} per user.
 */
@Component
@Slf4j
public class UsageAggregationClient {

    private static final DateTimeFormatter INDEX_DATE_SUFFIX = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final String USER_SOURCE = "user";
    private static final String AGG_BY_USER = "by_user";
    private static final String AGG_INSTANCES = "instances";
    private static final String AGG_BY_BUCKET = "by_bucket";
    private static final String AGG_BY_SERVICE = "by_service";
    private static final String AGG_USAGE = "usage";
    private static final String AGG_REPORTED_DAY = "reported_day";
    private static final String AGG_NAS_IP = "nas_ip";
    private static final String USERNAME_FIELD = "userName.keyword";
    /** Metadata field the NAS filter pins to the reported day's own index. */
    private static final String INDEX_FIELD = "_index";
    /** Excluded from an index expression, so the scan stops short of a day it must not read. */
    private static final String EXCLUDE = "-";

    private final ObjectProvider<ElasticsearchClient> clientProvider;
    private final UserDumpProperties properties;

    public UsageAggregationClient(ObjectProvider<ElasticsearchClient> clientProvider,
                                  UserDumpProperties properties) {
        this.clientProvider = clientProvider;
        this.properties = properties;
    }

    /** The daily index holding the sessions of {@code day}. */
    public String indexFor(LocalDate day) {
        return properties.getUsage().getIndex() + "-" + day.format(INDEX_DATE_SUFFIX);
    }

    /**
     * The index expression the totals are summed over: every daily index up to and including
     * {@code day}.
     *
     * <p>With {@code lookback-days} left at 0 that is the base pattern's wildcard, with the days
     * after {@code day} — today's hot index among them — struck out of it by name. The exclusions
     * are what keep the old guarantee intact now that the scan is no longer a single named day: a
     * dump aggregates history, never the index cdr-service is writing into as it runs.
     *
     * <p>A positive {@code lookback-days} names that many days instead, ending at {@code day}.
     * That bounds a scan on a cluster holding years of indices, at the cost of an index expression
     * that grows a name per day — past a few hundred days the wildcard is the setting that scales,
     * not a longer list.
     */
    public List<String> indicesUpTo(LocalDate day) {
        UserDumpProperties.Usage usage = properties.getUsage();

        int lookback = usage.getLookbackDays();
        if (lookback > 0) {
            List<String> indices = new ArrayList<>(lookback);
            for (int back = lookback - 1; back >= 0; back--) {
                indices.add(indexFor(day.minusDays(back)));
            }
            return indices;
        }

        List<String> indices = new ArrayList<>();
        indices.add(usage.getIndex() + "-*");
        LocalDate today = LocalDate.now(ZoneId.of(properties.getTimezone()));
        for (LocalDate after = day.plusDays(1); !after.isAfter(today); after = after.plusDays(1)) {
            indices.add(EXCLUDE + indexFor(after));
        }
        return indices;
    }

    /**
     * Opens a cursor over the session documents of every day up to {@code day}, restricted to a
     * shard's username range. The bucket totals it hands out cover all of them; the NAS address
     * beside them covers {@code day} alone.
     *
     * @param day            the day being reported on
     * @param usernameFrom   inclusive lower bound of the shard's username range, null for open
     * @param usernameTo     exclusive upper bound of the shard's username range, null for open
     */
    public UserUsageCursor open(LocalDate day, String usernameFrom, String usernameTo) {
        UserDumpProperties.Usage usage = properties.getUsage();
        if (!usage.isEnabled()) {
            log.info("Elasticsearch lookup disabled — UTLIZED_QUOTA and NAS_IP_ADDRESS will be left empty");
            return UserUsageCursor.empty();
        }

        ElasticsearchClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new ReportClientException("Elasticsearch client is not configured for the usage lookup", null);
        }
        return new CompositeUsageCursor(client, indicesUpTo(day), indexFor(day), usernameFrom, usernameTo, usage);
    }

    /**
     * Walks the composite aggregation page by page, handing out one user at a time. Only the
     * current page is held, so the cursor's footprint is the page size and not the user base.
     */
    private static final class CompositeUsageCursor extends UserUsageCursor {

        private final ElasticsearchClient client;
        private final List<String> indices;
        private final String reportedDayIndex;
        private final String usernameFrom;
        private final String usernameTo;
        private final UserDumpProperties.Usage config;

        private final Deque<UserUsage> page = new ArrayDeque<>();
        private Map<String, FieldValue> afterKey;
        private boolean exhausted;

        private CompositeUsageCursor(ElasticsearchClient client, List<String> indices,
                                     String reportedDayIndex, String usernameFrom,
                                     String usernameTo, UserDumpProperties.Usage config) {
            this.client = client;
            this.indices = indices;
            this.reportedDayIndex = reportedDayIndex;
            this.usernameFrom = usernameFrom;
            this.usernameTo = usernameTo;
            this.config = config;
        }

        @Override
        protected UserUsage fetchNext() {
            if (page.isEmpty() && !exhausted) {
                loadNextPage();
            }
            return page.poll();
        }

        private void loadNextPage() {
            try {
                SearchResponse<Void> response = client.search(buildRequest(), Void.class);
                var composite = response.aggregations().get(AGG_BY_USER).composite();
                List<CompositeBucket> buckets = composite.buckets().array();

                for (CompositeBucket bucket : buckets) {
                    UserUsage usage = toUserUsage(bucket);
                    if (usage != null) {
                        page.add(usage);
                    }
                }

                afterKey = composite.afterKey();
                // A short page or a missing after_key means the aggregation is done; either alone
                // is enough, and checking both avoids one wasted round trip on an exact multiple.
                exhausted = buckets.size() < config.getPageSize() || afterKey == null || afterKey.isEmpty();

            } catch (IOException e) {
                throw new ReportClientException("Failed to aggregate usage from indices " + indices, e);
            }
        }

        private SearchRequest buildRequest() {
            Map<String, CompositeAggregationSource> source = Map.of(
                    USER_SOURCE,
                    CompositeAggregationSource.of(s -> s.terms(t -> t.field(USERNAME_FIELD))));

            Aggregation byUser = new Aggregation.Builder()
                    .composite(c -> {
                        c.size(config.getPageSize()).sources(List.of(source));
                        if (afterKey != null && !afterKey.isEmpty()) {
                            c.after(afterKey);
                        }
                        return c;
                    })
                    .aggregations(subAggregations())
                    .build();

            SearchRequest.Builder request = new SearchRequest.Builder()
                    .index(indices)
                    // The dump must survive a day with no sessions, and must not fail the whole
                    // report because one day's index was rolled away.
                    .allowNoIndices(true)
                    .ignoreUnavailable(true)
                    .size(0)
                    .trackTotalHits(t -> t.enabled(false))
                    .aggregations(AGG_BY_USER, byUser);

            if (usernameFrom != null || usernameTo != null) {
                request.query(q -> q.bool(b -> b.filter(f -> f.range(r -> {
                    r.field(USERNAME_FIELD);
                    if (usernameFrom != null) {
                        r.gte(JsonData.of(usernameFrom));
                    }
                    if (usernameTo != null) {
                        r.lt(JsonData.of(usernameTo));
                    }
                    return r;
                }))));
            }

            return request.build();
        }

        private Map<String, Aggregation> subAggregations() {
            String instancesPath = config.getInstancesPath();
            String usageField = instancesPath + ".usage";

            if (!config.isNested()) {
                // Without a nested mapping Elasticsearch flattens the instance array, so a
                // per-bucket sum would credit every bucket with the whole session's usage. Sum
                // everything the user drew instead and report it as unattributed.
                return Map.of(
                        AGG_USAGE, Aggregation.of(a -> a.sum(s -> s.field(usageField))),
                        AGG_REPORTED_DAY, reportedDay());
            }

            Aggregation byBucket = new Aggregation.Builder()
                    .terms(t -> t.field(instancesPath + ".bucketId.keyword").size(config.getBucketsPerUser()))
                    .aggregations(bucketTotals(instancesPath, usageField))
                    .build();

            Aggregation instances = new Aggregation.Builder()
                    .nested(n -> n.path(instancesPath))
                    .aggregations(AGG_BY_BUCKET, byBucket)
                    .build();

            return Map.of(AGG_INSTANCES, instances, AGG_REPORTED_DAY, reportedDay());
        }

        /**
         * What is summed under one bucket: the bucket's own lifetime total, and — unless the dump
         * is told not to scope it — the same total split by the bundle that drew it.
         *
         * <p>Both are kept rather than only the split. A bucket id names the plan's bucket, not
         * one instance of it, so the split is what makes the figure the reported bundle's own;
         * the unsplit total beside it is what the dump falls back to when the CDR's serviceId
         * turns out not to be the SERVICE_INSTANCE.ID the database joined on, and it is also what
         * carries usage from any instance whose CDR named no service at all.
         */
        private Map<String, Aggregation> bucketTotals(String instancesPath, String usageField) {
            Aggregation total = Aggregation.of(a -> a.sum(s -> s.field(usageField)));
            if (!config.isScopeToService()) {
                return Map.of(AGG_USAGE, total);
            }

            Aggregation byService = new Aggregation.Builder()
                    .terms(t -> t.field(instancesPath + ".serviceId.keyword").size(config.getServicesPerUser()))
                    .aggregations(AGG_USAGE, u -> u.sum(s -> s.field(usageField)))
                    .build();

            return Map.of(AGG_USAGE, total, AGG_BY_SERVICE, byService);
        }

        /**
         * The NAS address, read inside a filter on the reported day's own index.
         *
         * <p>NAS_IP_ADDRESS reports the NAS that day's sessions were anchored to, and the usage
         * around it is now summed over every index up to that day — without the filter the column
         * would quietly start reporting whichever NAS a subscriber used most in all the history
         * the cluster still holds. The address is a scalar on the session document, not on an
         * instance, so this sits beside the usage aggregation whether or not the instances are
         * nested.
         */
        private Aggregation reportedDay() {
            Aggregation nasIp = new Aggregation.Builder()
                    .terms(t -> t.field(config.getNasIpField()).size(config.getNasAddressesPerUser()))
                    .build();

            return new Aggregation.Builder()
                    .filter(f -> f.term(t -> t.field(INDEX_FIELD).value(reportedDayIndex)))
                    .aggregations(AGG_NAS_IP, nasIp)
                    .build();
        }

        private UserUsage toUserUsage(CompositeBucket bucket) {
            FieldValue key = bucket.key().get(USER_SOURCE);
            if (key == null) {
                return null;
            }
            String userName = key.stringValue();
            String nasIpAddress = nasIpAddressOf(bucket);

            if (!config.isNested()) {
                double total = bucket.aggregations().get(AGG_USAGE).sum().value();
                return new UserUsage(userName, noBuckets(), noBuckets(), (long) total, false, nasIpAddress);
            }

            List<StringTermsBucket> bucketTerms = bucket.aggregations()
                    .get(AGG_INSTANCES).nested()
                    .aggregations().get(AGG_BY_BUCKET).sterms()
                    .buckets().array();

            Map<String, Long> perBucket = new HashMap<>(Math.max(4, bucketTerms.size() * 2));
            Map<String, Long> perServiceBucket = new HashMap<>(Math.max(4, bucketTerms.size() * 2));
            long total = 0L;
            for (StringTermsBucket term : bucketTerms) {
                String bucketId = term.key().stringValue();
                long value = (long) term.aggregations().get(AGG_USAGE).sum().value();
                perBucket.put(bucketId, value);
                total += value;
                splitByService(term, bucketId, perServiceBucket);
            }
            return new UserUsage(userName, perBucket, perServiceBucket, total, true, nasIpAddress);
        }

        /**
         * Records the bundle-by-bundle split of one bucket's total, where the dump asked for one.
         * A cluster whose CDRs never named a service answers with no terms, which is the same
         * shape as scoping being switched off and lands on the same fallback.
         */
        private void splitByService(StringTermsBucket bucketTerm, String bucketId,
                                    Map<String, Long> perServiceBucket) {
            Aggregate byService = bucketTerm.aggregations().get(AGG_BY_SERVICE);
            if (byService == null || !byService.isSterms()) {
                return;
            }
            for (StringTermsBucket service : byService.sterms().buckets().array()) {
                long value = (long) service.aggregations().get(AGG_USAGE).sum().value();
                perServiceBucket.put(
                        UserUsage.serviceBucketKey(service.key().stringValue(), bucketId), value);
            }
        }

        /**
         * The address most of the user's sessions on the reported day were anchored to, or null
         * when none of them reported one — a session cached before cdr-service recorded the field,
         * or a CDR that omitted it, leaves the document without the value rather than with an
         * empty one. A user whose sessions are all older than the reported day reaches here from
         * the same stream, with an empty filter and so with no address, which is what a user with
         * no session that day has always been reported as.
         */
        private String nasIpAddressOf(CompositeBucket bucket) {
            Aggregate day = bucket.aggregations().get(AGG_REPORTED_DAY);
            if (day == null || !day.isFilter()) {
                return null;
            }
            Aggregate aggregate = day.filter().aggregations().get(AGG_NAS_IP);
            // An index whose mapping predates the field answers with no terms at all — the dump
            // reports an empty NAS_IP_ADDRESS for that day rather than failing over it.
            if (aggregate == null || !aggregate.isSterms()) {
                return null;
            }
            List<StringTermsBucket> addresses = aggregate.sterms().buckets().array();
            return addresses.isEmpty() ? null : addresses.get(0).key().stringValue();
        }
    }

    /** Exposed for tests: the aggregation names this client reads back. */
    static List<String> aggregationNames() {
        return new ArrayList<>(List.of(AGG_BY_USER, AGG_INSTANCES, AGG_BY_BUCKET, AGG_BY_SERVICE,
                AGG_USAGE, AGG_REPORTED_DAY, AGG_NAS_IP));
    }
}
