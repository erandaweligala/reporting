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
import java.util.List;
import java.util.Map;

/**
 * Reads each user's NAS IP address out of Elasticsearch, one username at a time.
 *
 * <p>The documents are the session documents cdr-service writes: one per session in a daily
 * {@code radius-sessions-yyyy.MM.dd} index, each carrying the {@code nasIpAddress} the CDR every
 * accounting event is built from reported. AAA_USER has no column for it, so it is the one dump
 * column that comes from here rather than off the dump's own cursor.
 *
 * <p>NAS_IP_ADDRESS is the reported day's, so the reported day's index is all this reads. The
 * aggregation used to span every index the cluster held, because UTLIZED_QUOTA was summed from the
 * usage deltas on the same documents and a running total means every day of them; that column is
 * now BUCKET_INSTANCE.USAGE, read by the dump statement itself, and a scan of the cluster's whole
 * history is not something this column ever needed.
 *
 * <p>The addresses are read with a composite aggregation rather than fetched as hits: for 3 million
 * users the hits would be tens of millions of documents over the wire, whereas the aggregation
 * ships one address per user and pages deterministically through {@code after_key} with no scroll
 * context left open on the cluster. A user's sessions can name more than one NAS across a day, so
 * the address is read as a small terms aggregation and the most-used one wins — doc values only, no
 * fetch phase, which is what keeps 3 million users affordable next to a {@code top_hits} per user.
 */
@Component
@Slf4j
public class NasAddressAggregationClient {

    private static final DateTimeFormatter INDEX_DATE_SUFFIX = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final String USER_SOURCE = "user";
    private static final String AGG_BY_USER = "by_user";
    private static final String AGG_NAS_IP = "nas_ip";
    private static final String USERNAME_FIELD = "userName.keyword";

    private final ObjectProvider<ElasticsearchClient> clientProvider;
    private final UserDumpProperties properties;

    public NasAddressAggregationClient(ObjectProvider<ElasticsearchClient> clientProvider,
                                       UserDumpProperties properties) {
        this.clientProvider = clientProvider;
        this.properties = properties;
    }

    /** The daily index holding the sessions of {@code day}. */
    public String indexFor(LocalDate day) {
        return properties.getNasLookup().getIndex() + "-" + day.format(INDEX_DATE_SUFFIX);
    }

    /**
     * Opens a cursor over the reported day's session documents, restricted to a shard's username
     * range.
     *
     * @param day          the day being reported on
     * @param usernameFrom inclusive lower bound of the shard's username range, null for open
     * @param usernameTo   exclusive upper bound of the shard's username range, null for open
     */
    public UserNasAddressCursor open(LocalDate day, String usernameFrom, String usernameTo) {
        UserDumpProperties.NasLookup lookup = properties.getNasLookup();
        if (!lookup.isEnabled()) {
            log.info("Elasticsearch lookup disabled — NAS_IP_ADDRESS will be left empty");
            return UserNasAddressCursor.empty();
        }

        ElasticsearchClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new ReportClientException(
                    "Elasticsearch client is not configured for the NAS address lookup", null);
        }
        return new CompositeNasAddressCursor(client, indexFor(day), usernameFrom, usernameTo, lookup);
    }

    /**
     * Walks the composite aggregation page by page, handing out one user at a time. Only the
     * current page is held, so the cursor's footprint is the page size and not the user base.
     */
    private static final class CompositeNasAddressCursor extends UserNasAddressCursor {

        private final ElasticsearchClient client;
        private final String index;
        private final String usernameFrom;
        private final String usernameTo;
        private final UserDumpProperties.NasLookup config;

        private final Deque<UserNasAddress> page = new ArrayDeque<>();
        private Map<String, FieldValue> afterKey;
        private boolean exhausted;

        private CompositeNasAddressCursor(ElasticsearchClient client, String index,
                                          String usernameFrom, String usernameTo,
                                          UserDumpProperties.NasLookup config) {
            this.client = client;
            this.index = index;
            this.usernameFrom = usernameFrom;
            this.usernameTo = usernameTo;
            this.config = config;
        }

        @Override
        protected UserNasAddress fetchNext() {
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
                    UserNasAddress address = toUserNasAddress(bucket);
                    if (address != null) {
                        page.add(address);
                    }
                }

                afterKey = composite.afterKey();
                // A short page or a missing after_key means the aggregation is done; either alone
                // is enough, and checking both avoids one wasted round trip on an exact multiple.
                exhausted = buckets.size() < config.getPageSize() || afterKey == null || afterKey.isEmpty();

            } catch (IOException e) {
                throw new ReportClientException("Failed to aggregate NAS addresses from index " + index, e);
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
                    .aggregations(AGG_NAS_IP, nasIp())
                    .build();

            SearchRequest.Builder request = new SearchRequest.Builder()
                    .index(index)
                    // The dump must survive a day with no sessions, and must not fail the whole
                    // report because that day's index was rolled away.
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

        /** The addresses one user's sessions were anchored to, most-used first. */
        private Aggregation nasIp() {
            return new Aggregation.Builder()
                    .terms(t -> t.field(config.getNasIpField()).size(config.getNasAddressesPerUser()))
                    .build();
        }

        private UserNasAddress toUserNasAddress(CompositeBucket bucket) {
            FieldValue key = bucket.key().get(USER_SOURCE);
            if (key == null) {
                return null;
            }
            return new UserNasAddress(key.stringValue(), nasIpAddressOf(bucket));
        }

        /**
         * The address most of the user's sessions on the reported day were anchored to, or null
         * when none of them reported one — a session cached before cdr-service recorded the field,
         * or a CDR that omitted it, leaves the document without the value rather than with an
         * empty one.
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
        return new ArrayList<>(List.of(AGG_BY_USER, AGG_NAS_IP));
    }
}
