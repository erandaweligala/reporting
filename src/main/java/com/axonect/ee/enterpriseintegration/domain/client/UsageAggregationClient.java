package com.axonect.ee.enterpriseintegration.domain.client;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch.core.FieldCapsRequest;
import co.elastic.clients.elasticsearch.core.FieldCapsResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.field_caps.FieldCapability;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.util.NamedValue;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * the bucket's whole allowance, so the usage beside it is summed over every daily index the
 * cluster holds — up to and including the day the dump runs, not up to the day it reports on. A
 * subscriber who took their bundle out this morning has drawn everything they have drawn from
 * today's index, and striking that index out by name reported them as having no usage at all:
 * not a zero, but an empty column, because a user with no document anywhere in the scan is a user
 * the aggregation never returns.
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
 *
 * <p>That filter is a preference rather than the whole of the column, because the index a session
 * document lands in is the day the session <em>started</em>, not the days it covered. A session
 * opened this morning is in today's index; one opened last week and still up is in last week's.
 * Neither is in the reported day's, so a filter that only ever read that one index left those
 * users with an empty NAS_IP_ADDRESS beside a UTLIZED_QUOTA reporting that very session's usage —
 * the address was in the scan the whole time, one index over. So where the reported day's index
 * names no address for a user, the newest daily index that does is read instead: the NAS they were
 * last anchored to, not the one they used most across whatever history the cluster holds. Which of
 * the two answered is carried on the record, because the file cannot be read for it.
 *
 * <p>Which field that terms aggregation names is asked of the cluster rather than assumed, because
 * getting it wrong is invisible: a terms aggregation on a field the mapping does not have answers
 * with no terms and no error, so the dump writes an empty NAS_IP_ADDRESS for every user and the
 * run still looks like a success. The address is a sub-field under a {@code text} mapping and a
 * field in its own right under a {@code keyword} or an {@code ip} one, and the two spell the
 * aggregatable field differently — {@code nasIpAddress.keyword} against {@code nasIpAddress}. So
 * the spelling is resolved once per run against the reported day's own index, with a field
 * capabilities call, and the log says which one was taken or why the column will be empty. Nothing
 * else in this join needs that: {@code userName.keyword} and the two ids under
 * {@code sessionInstances} are proven by the usage figures themselves, which go wrong in a way
 * that shows rather than blank.
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
    private static final String AGG_LATEST_DAY = "latest_day";
    private static final String AGG_NAS_IP = "nas_ip";
    private static final String USERNAME_FIELD = "userName.keyword";
    /** Metadata field the NAS filter pins to the reported day's own index. */
    private static final String INDEX_FIELD = "_index";
    /** Terms ordering that makes the newest daily index — the highest name — the first bucket. */
    private static final String KEY_ORDER = "_key";

    /** Suffix that names the aggregatable sub-field of a {@code text} mapping. */
    private static final String KEYWORD_SUFFIX = ".keyword";

    private final ObjectProvider<ElasticsearchClient> clientProvider;
    private final UserDumpProperties properties;

    /**
     * NAS field resolved per reported-day index, so the shards of one run resolve it once between
     * them rather than once each. A run names a new index, so an entry is a run's worth of memory
     * and never a stale answer for the day being reported on.
     */
    private final Map<String, String> resolvedNasIpFields = new ConcurrentHashMap<>();

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
     * today, whichever day the dump is reporting on.
     *
     * <p>With {@code lookback-days} left at 0 that is the base pattern's wildcard and nothing
     * else. It used to strike the days after the reported one out of that wildcard by name, to
     * keep a dump off the index cdr-service is writing into — and that is what left a subscriber
     * whose sessions all started after the reported day with an empty UTLIZED_QUOTA rather than
     * with their usage. The total is read next to QUOTA, so it has to be what the bundle has drawn
     * as of the run; a delta cdr-service wrote an hour ago is as much a part of that as one it
     * wrote last week. Aggregating a live index costs the cluster a search, which is what every
     * other day of the scan costs it too.
     *
     * <p>The reported day still bounds everything it should: which bundle the dump reports, and —
     * through a filter of its own, not through this expression — which day's NAS address sits
     * beside the total.
     *
     * <p>A positive {@code lookback-days} names that many days instead, ending today. That bounds
     * a scan on a cluster holding years of indices, at the cost of an index expression that grows
     * a name per day — past a few hundred days the wildcard is the setting that scales, not a
     * longer list.
     */
    public List<String> indicesThroughToday() {
        UserDumpProperties.Usage usage = properties.getUsage();

        int lookback = usage.getLookbackDays();
        if (lookback <= 0) {
            return List.of(usage.getIndex() + "-*");
        }

        LocalDate today = LocalDate.now(ZoneId.of(properties.getTimezone()));
        List<String> indices = new ArrayList<>(lookback);
        for (int back = lookback - 1; back >= 0; back--) {
            indices.add(indexFor(today.minusDays(back)));
        }
        return indices;
    }

    /**
     * Opens a cursor over the session documents of every day up to today, restricted to a shard's
     * username range. The bucket totals it hands out cover all of them; the NAS address beside
     * them covers {@code day} alone. A caller with no NAS column to fill opens
     * {@link #openBucketTotals} instead.
     *
     * @param day            the day being reported on
     * @param usernameFrom   inclusive lower bound of the shard's username range, null for open
     * @param usernameTo     exclusive upper bound of the shard's username range, null for open
     */
    public UserUsageCursor open(LocalDate day, String usernameFrom, String usernameTo) {
        return openCursor(indexFor(day), usernameFrom, usernameTo);
    }

    /**
     * Opens a cursor over the bucket totals alone: the same figures, summed over the same indices,
     * with no NAS address beside them and so no reported day to filter one on.
     *
     * <p>This is what the BUCKET_INSTANCE extract reads. It reports one row per bucket rather than
     * one row per user, so the total each row wants is exactly the one UTLIZED_QUOTA is — asked
     * for by the same pair, the bucket's id and the service instance holding it — while the NAS
     * address the dump reads in the same pass belongs to a day the extract does not have and to a
     * column it does not carry. Leaving that sub-aggregation out of the request is a filter and a
     * terms aggregation the cluster does not run, per user, across the whole username keyspace.
     *
     * @param usernameFrom inclusive lower bound of the username range, null for open
     * @param usernameTo   exclusive upper bound of the username range, null for open
     */
    public UserUsageCursor openBucketTotals(String usernameFrom, String usernameTo) {
        return openCursor(null, usernameFrom, usernameTo);
    }

    /**
     * @param reportedDayIndex index the NAS address is read from, or null to leave that column —
     *                         and the aggregation behind it — out of the request altogether
     */
    private UserUsageCursor openCursor(String reportedDayIndex, String usernameFrom, String usernameTo) {
        UserDumpProperties.Usage usage = properties.getUsage();
        if (!usage.isEnabled()) {
            log.info("Elasticsearch lookup disabled — the columns filled from the CDR session "
                    + "documents will be left empty");
            return UserUsageCursor.empty();
        }

        ElasticsearchClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new ReportClientException("Elasticsearch client is not configured for the usage lookup", null);
        }
        // Only the dump fills NAS_IP_ADDRESS, so only the dump pays for resolving the field it is
        // read from; the bucket extract asks for no such column and reaches here with no day.
        String nasIpField = reportedDayIndex == null
                ? null
                : resolveNasIpField(client, reportedDayIndex, usage);

        return new CompositeUsageCursor(client, indicesThroughToday(), reportedDayIndex, nasIpField,
                usernameFrom, usernameTo, usage);
    }

    /**
     * The field NAS_IP_ADDRESS is aggregated on, as the reported day's index actually maps it.
     *
     * <p>Configuration names the spelling to prefer; the cluster decides whether that spelling is
     * there. Both outcomes are the same shape to a terms aggregation — no terms — so a field that
     * is not in the mapping cannot be told from a user with no NAS by looking at the answer, and
     * the whole column comes out empty without a single error. Asking first is one request per
     * run against one index, and it turns that silence into a line in the log.
     */
    private String resolveNasIpField(ElasticsearchClient client, String reportedDayIndex,
                                     UserDumpProperties.Usage usage) {
        String configured = usage.getNasIpField();
        if (configured == null || configured.isEmpty()) {
            log.warn("report.user-dump.usage.nas-ip-field names no field, so NAS_IP_ADDRESS is "
                    + "left empty and the aggregation behind it is left out of the request");
            return null;
        }
        return resolvedNasIpFields.computeIfAbsent(reportedDayIndex + '\u0000' + configured,
                key -> lookUpNasIpField(client, reportedDayIndex, configured));
    }

    /**
     * Asks the reported day's index which of the candidate spellings it can aggregate, and takes
     * the first it can.
     *
     * <p>A cluster that will not answer — the call is refused, the version does not carry the API,
     * anything at all — leaves the configured spelling in place, which is exactly the behaviour
     * this replaces: the aggregation is tried, and the column is empty if the guess was wrong.
     * Nothing this method can run into is worth failing a 3 million row dump over, so it catches
     * broadly on purpose; the other 38 columns do not depend on it. What is not left alone is the
     * log. Every way this can end with an empty NAS_IP_ADDRESS says so here, naming the index it
     * asked and the fields it asked for, because that column failing quietly is what this lookup
     * exists to stop.
     */
    private String lookUpNasIpField(ElasticsearchClient client, String index, String configured) {
        List<String> candidates = nasIpFieldCandidates(configured);
        try {
            FieldCapsResponse caps = client.fieldCaps(new FieldCapsRequest.Builder()
                    .index(index)
                    .fields(candidates)
                    // The day's index can be absent — rolled away, or a dump run before
                    // cdr-service wrote anything that day — and that is a log line, not a failure
                    // of the run: every other column of the dump is still there to be written.
                    .allowNoIndices(true)
                    .ignoreUnavailable(true)
                    .build());

            for (String candidate : candidates) {
                if (isAggregatable(caps, candidate)) {
                    if (!candidate.equals(configured)) {
                        log.info("NAS_IP_ADDRESS will be read from {}: {} is not an aggregatable "
                                        + "field of {}, which maps the address without the {} "
                                        + "sub-field a text mapping would add",
                                candidate, configured, index, KEYWORD_SUFFIX);
                    }
                    return candidate;
                }
            }

            log.error("NAS_IP_ADDRESS will be empty for every user: none of {} is an aggregatable "
                            + "field of {}. {} — set report.user-dump.usage.nas-ip-field to the "
                            + "field cdr-service records the NAS address under",
                    candidates, index, whatAnswered(caps));

        } catch (IOException | RuntimeException e) {
            log.warn("Could not ask {} which field maps the NAS address; NAS_IP_ADDRESS will be "
                            + "read from the configured {} and will be empty if that is not the "
                            + "field the mapping carries", index, configured, e);
        }
        return configured;
    }

    /** Whether {@code field} is a field of the answered indices that a terms aggregation can read. */
    private boolean isAggregatable(FieldCapsResponse caps, String field) {
        Map<String, FieldCapability> byType = caps.fields().get(field);
        if (byType == null) {
            return false;
        }
        return byType.values().stream().anyMatch(FieldCapability::aggregatable);
    }

    /**
     * Which of the two ways the lookup came back empty this was, so the log says whether the index
     * or the field is the thing that is missing. They need different fixes and read alike in the
     * file: an absent index is a naming or a timezone that does not match cdr-service's, while an
     * index that answered with neither field is a mapping the configured field does not describe.
     */
    private String whatAnswered(FieldCapsResponse caps) {
        return caps.indices().isEmpty()
                ? "No index of that name answered, so the reported day's sessions are not under it"
                        + " — check report.user-dump.usage.index and report.user-dump.timezone"
                        + " against the daily indices cdr-service writes"
                : "The index answered, so it holds the day's sessions under some other field";
    }

    /**
     * The spellings of the NAS field worth trying, most preferred first: the configured one, then
     * the same name with the {@code .keyword} sub-field added or taken away.
     *
     * <p>Those two are the same mapping decision seen from either side. cdr-service writing the
     * address into a {@code text} field makes {@code nasIpAddress.keyword} the aggregatable one
     * and {@code nasIpAddress} analysed and unaggregatable; mapping it as {@code keyword} or as
     * {@code ip} — which is what a template written for an address does — makes
     * {@code nasIpAddress} the aggregatable one and {@code nasIpAddress.keyword} nothing at all.
     * A deployment that renames the field past either of those still overrides
     * {@code nas-ip-field}, and its override is what is preferred here.
     */
    static List<String> nasIpFieldCandidates(String configured) {
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }
        String sibling = configured.endsWith(KEYWORD_SUFFIX)
                ? configured.substring(0, configured.length() - KEYWORD_SUFFIX.length())
                : configured + KEYWORD_SUFFIX;
        return sibling.isEmpty() ? List.of(configured) : List.of(configured, sibling);
    }

    /**
     * Walks the composite aggregation page by page, handing out one user at a time. Only the
     * current page is held, so the cursor's footprint is the page size and not the user base.
     */
    private static final class CompositeUsageCursor extends UserUsageCursor {

        private final ElasticsearchClient client;
        private final List<String> indices;
        private final String reportedDayIndex;
        /** Field the NAS terms aggregation names, as the reported day's index maps it. */
        private final String nasIpField;
        private final String usernameFrom;
        private final String usernameTo;
        private final UserDumpProperties.Usage config;

        private final Deque<UserUsage> page = new ArrayDeque<>();
        private Map<String, FieldValue> afterKey;
        private boolean exhausted;

        private CompositeUsageCursor(ElasticsearchClient client, List<String> indices,
                                     String reportedDayIndex, String nasIpField,
                                     String usernameFrom, String usernameTo,
                                     UserDumpProperties.Usage config) {
            this.client = client;
            this.indices = indices;
            this.reportedDayIndex = reportedDayIndex;
            this.nasIpField = nasIpField;
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

            Map<String, Aggregation> aggregations = new HashMap<>(4);
            // Null means no column is being filled from it, which is the bucket extract: it has no
            // reported day and no NAS column, so the filter and the terms aggregation under it are
            // left out of the request rather than run per user and dropped. A dump that could not
            // be told which field maps the address leaves them out for the same reason.
            if (reportedDayIndex != null && nasIpField != null) {
                aggregations.put(AGG_REPORTED_DAY, reportedDay());
                if (config.isNasIpFallbackToLatestDay()) {
                    aggregations.put(AGG_LATEST_DAY, latestDay());
                }
            }

            if (!config.isNested()) {
                // Without a nested mapping Elasticsearch flattens the instance array, so a
                // per-bucket sum would credit every bucket with the whole session's usage. Sum
                // everything the user drew instead and report it as unattributed.
                aggregations.put(AGG_USAGE, Aggregation.of(a -> a.sum(s -> s.field(usageField))));
                return aggregations;
            }

            Aggregation byBucket = new Aggregation.Builder()
                    .terms(t -> t.field(instancesPath + ".bucketId.keyword").size(config.getBucketsPerUser()))
                    .aggregations(bucketTotals(instancesPath, usageField))
                    .build();

            Aggregation instances = new Aggregation.Builder()
                    .nested(n -> n.path(instancesPath))
                    .aggregations(AGG_BY_BUCKET, byBucket)
                    .build();

            aggregations.put(AGG_INSTANCES, instances);
            return aggregations;
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
         * around it is summed over every index the cluster holds — without the filter the column
         * would quietly start reporting whichever NAS a subscriber used most in all the history
         * the cluster still holds, today's sessions included. The address is a scalar on the
         * session document, not on an instance, so this sits beside the usage aggregation whether
         * or not the instances are nested.
         *
         * <p>It is the preferred source rather than the only one: a user with no session document
         * filed under that day falls back to {@link #latestDay()}, which is where a session that
         * started on either side of the reported day has its address.
         */
        private Aggregation reportedDay() {
            return new Aggregation.Builder()
                    .filter(f -> f.term(t -> t.field(INDEX_FIELD).value(reportedDayIndex)))
                    .aggregations(AGG_NAS_IP, nasIpAddresses())
                    .build();
        }

        /**
         * The same addresses, read from the newest daily index the user appears in at all — what
         * fills NAS_IP_ADDRESS where the reported day's own index names none.
         *
         * <p>It is needed because a session document is filed under the day its session
         * <em>started</em>, which is not the day, or the days, that session covers. A subscriber
         * who came up this morning has their address in today's index; one who came up last week
         * and has not dropped since — an always-on FTTH line, which is most of a base — has it in
         * last week's. Neither appears in the reported day's index at all, so the filter above
         * finds nothing for them and the column came out empty next to a UTLIZED_QUOTA reporting
         * that same session's usage, because the totals are summed over every index in the scan.
         *
         * <p>A terms aggregation on {@code _index} ordered by key descending, kept to one bucket,
         * is that newest index: the daily names sort by date because they are written
         * {@code yyyy.MM.dd}. Ordering by the key rather than by doc count is also what makes the
         * single bucket exact — every shard holds one index's documents and so answers with its
         * own name, and the merge keeps the highest of them — and it is the newest index that is
         * wanted rather than the busiest, which would be the many-year "NAS they used most" that
         * the reported day's filter exists to avoid reporting.
         *
         * <p>It reads the same field, resolved the same way, and stays inside the same pass: one
         * more doc-values aggregation per user, not a lookup of its own. That field is the one the
         * reported day's mapping can aggregate, which is the mapping the run resolved against — an
         * older index that spells the address the other way answers this with no terms, the same
         * empty column it would have given anyway, and the shard's count says how many rows are
         * still without an address.
         */
        private Aggregation latestDay() {
            return new Aggregation.Builder()
                    .terms(t -> t.field(INDEX_FIELD)
                            .size(1)
                            .order(NamedValue.of(KEY_ORDER, SortOrder.Desc)))
                    .aggregations(AGG_NAS_IP, nasIpAddresses())
                    .build();
        }

        /**
         * The addresses a set of the user's sessions named, most used first. The field is the one
         * {@link #resolveNasIpField} found in the reported day's own mapping, not the configured
         * spelling as it stands: the two differ by a {@code .keyword} sub-field that a
         * {@code text} mapping has and a {@code keyword} or {@code ip} one does not, and
         * aggregating the wrong one of them is what empties the column for every user without
         * raising anything.
         */
        private Aggregation nasIpAddresses() {
            return new Aggregation.Builder()
                    .terms(t -> t.field(nasIpField).size(config.getNasAddressesPerUser()))
                    .build();
        }

        private UserUsage toUserUsage(CompositeBucket bucket) {
            FieldValue key = bucket.key().get(USER_SOURCE);
            if (key == null) {
                return null;
            }
            String userName = key.stringValue();
            String nasIpAddress = nasIpAddressOf(bucket, AGG_REPORTED_DAY);
            // Only where the reported day's own index named none: a session that started before
            // that day and ran through it, or one that started after it, is filed under another
            // index and is all the cluster holds for such a user.
            boolean fromAnotherDay = false;
            if (nasIpAddress == null) {
                nasIpAddress = nasIpAddressOf(bucket, AGG_LATEST_DAY);
                fromAnotherDay = nasIpAddress != null;
            }

            if (!config.isNested()) {
                double total = bucket.aggregations().get(AGG_USAGE).sum().value();
                return new UserUsage(userName, noBuckets(), noBuckets(), Set.of(), (long) total,
                        false, nasIpAddress, fromAnotherDay);
            }

            List<StringTermsBucket> bucketTerms = bucket.aggregations()
                    .get(AGG_INSTANCES).nested()
                    .aggregations().get(AGG_BY_BUCKET).sterms()
                    .buckets().array();

            Map<String, Long> perBucket = new HashMap<>(Math.max(4, bucketTerms.size() * 2));
            Map<String, Long> perServiceBucket = new HashMap<>(Math.max(4, bucketTerms.size() * 2));
            Set<String> splitBuckets = new HashSet<>(Math.max(4, bucketTerms.size() * 2));
            long total = 0L;
            for (StringTermsBucket term : bucketTerms) {
                String bucketId = term.key().stringValue();
                long value = (long) term.aggregations().get(AGG_USAGE).sum().value();
                perBucket.put(bucketId, value);
                total += value;
                if (splitByService(term, bucketId, perServiceBucket)) {
                    splitBuckets.add(bucketId);
                }
            }
            return new UserUsage(userName, perBucket, perServiceBucket, splitBuckets, total, true,
                    nasIpAddress, fromAnotherDay);
        }

        /**
         * Records the bundle-by-bundle split of one bucket's total, where the dump asked for one.
         * A cluster whose CDRs never named a service answers with no terms, which is the same
         * shape as scoping being switched off and lands on the same fallback.
         *
         * @return whether the split can be read as the whole of who drew on this bucket, which is
         *         what lets a bundle it does not name be reported as having drawn nothing rather
         *         than as the bucket's total across bundles. A terms aggregation that left
         *         documents out — more bundles touched the bucket than {@code services-per-user}
         *         has room for — cannot say that, and neither can one that came back empty
         */
        private boolean splitByService(StringTermsBucket bucketTerm, String bucketId,
                                       Map<String, Long> perServiceBucket) {
            Aggregate byService = bucketTerm.aggregations().get(AGG_BY_SERVICE);
            if (byService == null || !byService.isSterms()) {
                return false;
            }
            StringTermsAggregate split = byService.sterms();
            List<StringTermsBucket> services = split.buckets().array();
            for (StringTermsBucket service : services) {
                long value = (long) service.aggregations().get(AGG_USAGE).sum().value();
                perServiceBucket.put(
                        UserUsage.serviceBucketKey(service.key().stringValue(), bucketId), value);
            }
            return !services.isEmpty() && split.sumOtherDocCount() == 0L;
        }

        /**
         * The address most of the user's sessions under {@code dayAggregation} were anchored to,
         * or null when that day has no sessions of theirs or none of those named a NAS — a session
         * cached before cdr-service recorded the field, or a CDR that omitted it, leaves the
         * document without the value rather than with an empty one.
         *
         * <p>The two days are the same shape once unwrapped, and differ only in how they were
         * selected: {@link #AGG_REPORTED_DAY} is a filter pinned to the reported day's index, and
         * {@link #AGG_LATEST_DAY} the newest index the user appears in, which is read only when
         * the first named nothing. A caller that asked for neither — the bucket extract — finds
         * no aggregation under either name and is reported with no address at all, exactly as it
         * was before the fallback existed.
         */
        private String nasIpAddressOf(CompositeBucket bucket, String dayAggregation) {
            Aggregate day = bucket.aggregations().get(dayAggregation);
            if (day == null) {
                return null;
            }
            Aggregate addresses = day.isFilter()
                    ? day.filter().aggregations().get(AGG_NAS_IP)
                    : newestDayOf(day);
            // An index whose mapping predates the field answers with no terms at all — the dump
            // reports an empty NAS_IP_ADDRESS for that user rather than failing over it.
            if (addresses == null || !addresses.isSterms()) {
                return null;
            }
            List<StringTermsBucket> found = addresses.sterms().buckets().array();
            return found.isEmpty() ? null : found.get(0).key().stringValue();
        }

        /** The one index bucket {@link #latestDay()} keeps, unwrapped to the addresses under it. */
        private Aggregate newestDayOf(Aggregate day) {
            if (!day.isSterms()) {
                return null;
            }
            List<StringTermsBucket> days = day.sterms().buckets().array();
            return days.isEmpty() ? null : days.get(0).aggregations().get(AGG_NAS_IP);
        }
    }

    /** Exposed for tests: the aggregation names this client reads back. */
    static List<String> aggregationNames() {
        return new ArrayList<>(List.of(AGG_BY_USER, AGG_INSTANCES, AGG_BY_BUCKET, AGG_BY_SERVICE,
                AGG_USAGE, AGG_REPORTED_DAY, AGG_LATEST_DAY, AGG_NAS_IP));
    }
}
