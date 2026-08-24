package com.axonect.ee.enterpriseintegration.domain.client;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Totals each user's session usage per bucket for one day, out of the {@code radius-sessions-*}
 * indices cdr-service writes.
 *
 * <p>Usage is read the way cdr-service records it: every accounting event appends a
 * {@code sessionInstances} entry holding that event's usage delta and the bucket it was charged
 * to, so a user's total for a bucket is the sum of {@code sessionInstances.usage} over their
 * sessions in that day's index. Index naming, the daily suffix and the "skip indices that do not
 * exist" behaviour all follow cdr-service's {@code ConnectionHistoryService}.
 *
 * <p>The aggregation is done once per page of users, not once per user: a composite aggregation
 * keyed on {@code userName x bucketId} returns every total for the page in one request (paged by
 * {@code after_key} when a page has more cells than fit in one response). Asking per user would be
 * one request per row — millions of round trips for a full dump.
 *
 * <p>One caveat on the mapping: cdr-service lets {@code sessionInstances} be mapped dynamically,
 * which makes it an object array rather than a {@code nested} field, so a document's instance
 * usages are flattened into one list and cannot be correlated per-element with their bucket ids.
 * That is exact as long as a session charges a single bucket, which is how cdr-service assigns
 * them. If the index is ever remapped as {@code nested}, this aggregation has to be wrapped in a
 * nested aggregation to stay exact.
 */
@Component
@Slf4j
public class SessionUsageAggregator {

    private static final DateTimeFormatter INDEX_DATE_SUFFIX = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private static final String AGG_NAME = "usage_by_user_bucket";
    private static final String AGG_USAGE = "usage";
    private static final String SRC_USER = "user";
    private static final String SRC_BUCKET = "bucket";

    private static final String FIELD_USER_NAME = "userName.keyword";
    private static final String FIELD_BUCKET_ID = "sessionInstances.bucketId.keyword";
    private static final String FIELD_USAGE = "sessionInstances.usage";

    /**
     * Separates the two halves of the lookup key. NUL is used rather than a printable character so
     * that no user name or bucket id can contain it and collide two different pairs onto one key.
     */
    private static final char KEY_SEPARATOR = '\0';

    /**
     * The composite key: one cell per user per bucket. Immutable, so it is built once and reused
     * across every page and every request.
     */
    private static final List<Map<String, CompositeAggregationSource>> COMPOSITE_SOURCES = List.of(
            Map.of(SRC_USER, CompositeAggregationSource.of(
                    cs -> cs.terms(t -> t.field(FIELD_USER_NAME)))),
            Map.of(SRC_BUCKET, CompositeAggregationSource.of(
                    cs -> cs.terms(t -> t.field(FIELD_BUCKET_ID))))
    );

    private final ElasticsearchClient client;

    @Value("${report.user-dump.elk.index-prefix:radius-sessions}")
    private String indexPrefix;

    @Value("${report.user-dump.elk.composite-page-size:5000}")
    private int compositePageSize;

    public SessionUsageAggregator(ElasticsearchClient reportElasticsearchClient) {
        this.client = reportElasticsearchClient;
    }

    /**
     * The lookup key for one user/bucket pair, as used by {@link #usageFor}.
     */
    public static String key(String userName, String bucketId) {
        return userName + KEY_SEPARATOR + bucketId;
    }

    /**
     * Total usage for one user/bucket pair, or null when the day's index holds no usage for it.
     */
    public static Long usageFor(Map<String, Long> usage, String userName, String bucketId) {
        if (userName == null || bucketId == null) {
            return null;
        }
        return usage.get(key(userName, bucketId));
    }

    /**
     * Total per-bucket usage for the given users on the given day.
     *
     * @return usage keyed by {@link #key(String, String)}; empty when the day has no index. Users
     *         with no sessions that day are simply absent.
     */
    public Map<String, Long> aggregateUsage(List<String> userNames, LocalDate day) {
        if (userNames.isEmpty()) {
            return Map.of();
        }

        String index = indexFor(day);
        if (!indexExists(index)) {
            log.warn("Session index {} does not exist; usage will be reported as empty for {} user(s)",
                    index, userNames.size());
            return Map.of();
        }

        List<FieldValue> terms = userNames.stream().map(FieldValue::of).toList();
        Map<String, Long> usage = new HashMap<>();
        Map<String, FieldValue> after = null;

        try {
            while (true) {
                final Map<String, FieldValue> afterKey = after;
                SearchResponse<Void> response = client.search(s -> s
                                .index(index)
                                .size(0)
                                .trackTotalHits(t -> t.enabled(false))
                                .query(q -> q.bool(b -> b
                                        .filter(f -> f.terms(t -> t
                                                .field(FIELD_USER_NAME)
                                                .terms(tv -> tv.value(terms))))))
                                .aggregations(AGG_NAME, a -> a
                                        .composite(c -> {
                                            c.size(compositePageSize).sources(COMPOSITE_SOURCES);
                                            if (afterKey != null) {
                                                c.after(afterKey);
                                            }
                                            return c;
                                        })
                                        .aggregations(AGG_USAGE, sub -> sub
                                                .sum(sum -> sum.field(FIELD_USAGE)))),
                        Void.class);

                Aggregate aggregate = response.aggregations().get(AGG_NAME);
                if (aggregate == null || !aggregate.isComposite()) {
                    break;
                }

                List<CompositeBucket> buckets = aggregate.composite().buckets().array();
                for (CompositeBucket bucket : buckets) {
                    String user = stringValue(bucket.key().get(SRC_USER));
                    String bucketId = stringValue(bucket.key().get(SRC_BUCKET));
                    if (user == null || bucketId == null) {
                        continue;
                    }
                    Aggregate sum = bucket.aggregations().get(AGG_USAGE);
                    if (sum != null && sum.isSum()) {
                        usage.put(key(user, bucketId), (long) sum.sum().value());
                    }
                }

                after = aggregate.composite().afterKey();
                if (after == null || after.isEmpty() || buckets.size() < compositePageSize) {
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            // A dump of this size is worth delivering with usage missing rather than not at all;
            // the shortfall is logged and the affected rows carry an empty UTLIZED_QUOTA.
            log.error("Failed to aggregate session usage from index {} for {} user(s); "
                    + "usage will be empty for this page", index, userNames.size(), e);
            return Map.of();
        }

        log.debug("Aggregated usage for {} user(s) from {}: {} user/bucket total(s)",
                userNames.size(), index, usage.size());
        return usage;
    }

    /**
     * The daily index a day's sessions were written to, matching cdr-service's naming.
     */
    public String indexFor(LocalDate day) {
        return indexPrefix + "-" + day.format(INDEX_DATE_SUFFIX);
    }

    private boolean indexExists(String index) {
        try {
            return client.indices().exists(e -> e.index(index)).value();
        } catch (IOException | RuntimeException e) {
            log.warn("Could not check existence of index {}: {}", index, e.getMessage());
            return false;
        }
    }

    private static String stringValue(FieldValue value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isString() ? value.stringValue() : String.valueOf(value._get());
    }
}
