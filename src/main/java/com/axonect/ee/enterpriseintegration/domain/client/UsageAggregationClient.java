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
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates a day of CDR usage out of Elasticsearch, one username at a time.
 *
 * <p>The documents are the session documents cdr-service writes: one per session in a daily
 * {@code radius-sessions-yyyy.MM.dd} index, each carrying a {@code sessionInstances} array whose
 * entries hold a {@code usage} delta and the {@code bucketId} it was drawn from. Reading a single
 * day means naming the single index directly rather than searching a wildcard, which is both the
 * cheapest possible shard selection and the reason a D-1 dump never touches today's hot index.
 *
 * <p>Usage is summed with a composite aggregation rather than fetched as hits: for 3 million users
 * the hits would be tens of millions of documents over the wire, whereas the aggregation ships one
 * number per user per bucket and pages deterministically through {@code after_key} with no scroll
 * context left open on the cluster.
 *
 * <p>The same pass also reports each user's NAS IP address, which AAA_USER does not carry: it is
 * the {@code nasIpAddress} cdr-service records on the session document from the CDR every
 * accounting event carries. A user's sessions can name more than one NAS across a day, so it is
 * read as a small terms aggregation and the most-used address wins — doc values only, no fetch
 * phase, which is what keeps 3 million users affordable next to a {@code top_hits} per user.
 */
@Component
@Slf4j
public class UsageAggregationClient {

    private static final DateTimeFormatter INDEX_DATE_SUFFIX = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final String USER_SOURCE = "user";
    private static final String AGG_BY_USER = "by_user";
    private static final String AGG_INSTANCES = "instances";
    private static final String AGG_BY_BUCKET = "by_bucket";
    private static final String AGG_USAGE = "usage";
    private static final String AGG_NAS_IP = "nas_ip";
    private static final String USERNAME_FIELD = "userName.keyword";

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
     * Opens a cursor over one day's session documents, restricted to a shard's username range.
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
        return new CompositeUsageCursor(client, indexFor(day), usernameFrom, usernameTo, usage);
    }

    /**
     * Walks the composite aggregation page by page, handing out one user at a time. Only the
     * current page is held, so the cursor's footprint is the page size and not the user base.
     */
    private static final class CompositeUsageCursor extends UserUsageCursor {

        private final ElasticsearchClient client;
        private final String index;
        private final String usernameFrom;
        private final String usernameTo;
        private final UserDumpProperties.Usage config;

        private final Deque<UserUsage> page = new ArrayDeque<>();
        private Map<String, FieldValue> afterKey;
        private boolean exhausted;

        private CompositeUsageCursor(ElasticsearchClient client, String index, String usernameFrom,
                                     String usernameTo, UserDumpProperties.Usage config) {
            this.client = client;
            this.index = index;
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
                throw new ReportClientException("Failed to aggregate usage from index " + index, e);
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
                    .index(index)
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

            // The NAS address is a scalar on the session document, not on an instance, so it is
            // read beside the usage aggregation whether or not the instances are nested.
            Aggregation nasIp = new Aggregation.Builder()
                    .terms(t -> t.field(config.getNasIpField()).size(config.getNasAddressesPerUser()))
                    .build();

            if (!config.isNested()) {
                // Without a nested mapping Elasticsearch flattens the instance array, so a
                // per-bucket sum would credit every bucket with the whole session's usage. Sum the
                // user's day instead and report it as unattributed.
                return Map.of(
                        AGG_USAGE, Aggregation.of(a -> a.sum(s -> s.field(usageField))),
                        AGG_NAS_IP, nasIp);
            }

            Aggregation byBucket = new Aggregation.Builder()
                    .terms(t -> t.field(instancesPath + ".bucketId.keyword").size(config.getBucketsPerUser()))
                    .aggregations(AGG_USAGE, u -> u.sum(s -> s.field(usageField)))
                    .build();

            Aggregation instances = new Aggregation.Builder()
                    .nested(n -> n.path(instancesPath))
                    .aggregations(AGG_BY_BUCKET, byBucket)
                    .build();

            return Map.of(AGG_INSTANCES, instances, AGG_NAS_IP, nasIp);
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
                return new UserUsage(userName, noBuckets(), (long) total, false, nasIpAddress);
            }

            List<StringTermsBucket> bucketTerms = bucket.aggregations()
                    .get(AGG_INSTANCES).nested()
                    .aggregations().get(AGG_BY_BUCKET).sterms()
                    .buckets().array();

            Map<String, Long> perBucket = new HashMap<>(Math.max(4, bucketTerms.size() * 2));
            long total = 0L;
            for (StringTermsBucket term : bucketTerms) {
                long value = (long) term.aggregations().get(AGG_USAGE).sum().value();
                perBucket.put(term.key().stringValue(), value);
                total += value;
            }
            return new UserUsage(userName, perBucket, total, true, nasIpAddress);
        }

        /**
         * The address most of the user's sessions were anchored to, or null when none of them
         * reported one — a session cached before cdr-service recorded the field, or a CDR that
         * omitted it, leaves the document without the value rather than with an empty one.
         */
        private String nasIpAddressOf(CompositeBucket bucket) {
            Aggregate aggregate = bucket.aggregations().get(AGG_NAS_IP);
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
        return new ArrayList<>(List.of(AGG_BY_USER, AGG_INSTANCES, AGG_BY_BUCKET, AGG_USAGE, AGG_NAS_IP));
    }
}
