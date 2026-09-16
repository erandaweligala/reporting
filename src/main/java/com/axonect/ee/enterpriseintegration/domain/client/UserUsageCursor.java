package com.axonect.ee.enterpriseintegration.domain.client;

import com.axonect.ee.enterpriseintegration.domain.util.Utf8Order;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * A forward-only stream of per-user CDR figures, ordered by username, that a caller walking users
 * in the same order can probe without ever holding more than one user's figures.
 *
 * <p>This is the half of the dump that keeps the Elasticsearch join off the heap. Materialising
 * every user's usage as a map would cost hundreds of megabytes and a full aggregation scan before
 * the first CSV row could be written; instead both sides are read in username order and
 * merge-joined, so memory is bounded by one Elasticsearch page regardless of user count and the
 * two scans overlap.
 *
 * <p>Each user is handed out exactly once, so everything the dump takes from Elasticsearch — the
 * total drawn from each bucket and the NAS the user's sessions were anchored to — has to come out
 * of that single probe. That is why {@link #forUser(String)} returns the whole record rather than
 * one figure at a time: a second probe for the same username would find the stream already past
 * it.
 */
public abstract class UserUsageCursor implements AutoCloseable {

    /** Figures for the current user, or null once the stream is exhausted. */
    private UserUsage head;

    /**
     * What a user drew from the CDR indices the dump aggregated: the total usage of each bucket
     * they touched, the same totals split by the bundle that drew them, and the NAS IP address
     * their sessions were anchored to — the reported day's where that day's index holds sessions
     * of theirs, and otherwise their most recent session's, since a session document is filed
     * under the day the session started rather than the days it covered.
     *
     * <p>The bucket totals are lifetime figures, not a day's: UTLIZED_QUOTA is read next to QUOTA,
     * which is the bucket's whole allowance, so the usage beside it has to be everything drawn
     * from that bucket as of the run — not the slice of it that happened to fall on D-1, and not
     * everything up to D-1 either, which left a bundle taken out this morning reporting nothing
     * at all.
     *
     * @param userName          the username the CDR documents were grouped under
     * @param perBucket         total usage per bucket id, empty when the split is not trustworthy
     * @param perServiceBucket  the same totals per {@link #serviceBucketKey service and bucket},
     *                          empty when the usage is not being scoped to a bundle
     * @param splitBuckets      bucket ids whose bundle-by-bundle split in {@code perServiceBucket}
     *                          is known to be complete — the CDRs named a bundle for every delta
     *                          and the aggregation had room for all of them. Only such a bucket can
     *                          say that a bundle missing from the split drew nothing; see
     *                          {@link #usageOn(String, String, String)}
     * @param total             usage across every bucket the user touched
     * @param attributable      whether {@code perBucket} may be read
     * @param nasIpAddress      NAS the user was anchored to, null when none was recorded
     * @param nasIpFromAnotherDay whether that address came from the user's most recent session day
     *                          instead of the reported one, which is where the address of a
     *                          session that started on either side of the reported day is filed.
     *                          The column reads the same either way; the dump counts them so a run
     *                          whose reported day has no index of its own is not read as a day's
     *                          worth of addresses
     */
    public record UserUsage(String userName, Map<String, Long> perBucket,
                            Map<String, Long> perServiceBucket, Set<String> splitBuckets,
                            long total, boolean attributable, String nasIpAddress,
                            boolean nasIpFromAnotherDay) {

        /** Separator between the two halves of a {@link #perServiceBucket} key. */
        private static final char KEY_SEPARATOR = '\u0000';

        public UserUsage {
            splitBuckets = splitBuckets == null ? Set.of() : splitBuckets;
        }

        /** A record whose address, where it has one, is the reported day's own. */
        public UserUsage(String userName, Map<String, Long> perBucket,
                         Map<String, Long> perServiceBucket, Set<String> splitBuckets,
                         long total, boolean attributable, String nasIpAddress) {
            this(userName, perBucket, perServiceBucket, splitBuckets, total, attributable,
                    nasIpAddress, false);
        }

        /**
         * A record whose splits carry no completeness of their own, which is every caller that
         * builds one by hand. Nothing is claimed about the bundles a bucket was split by, so a
         * bundle the split does not name falls back the way it always has.
         */
        public UserUsage(String userName, Map<String, Long> perBucket,
                         Map<String, Long> perServiceBucket, long total,
                         boolean attributable, String nasIpAddress) {
            this(userName, perBucket, perServiceBucket, Set.of(), total, attributable, nasIpAddress);
        }

        /** How {@link #perServiceBucket} is keyed, so the client fills it the way this reads it. */
        public static String serviceBucketKey(String serviceId, String bucketId) {
            return serviceId + KEY_SEPARATOR + bucketId;
        }

        /** Usage to report against {@code bucketId} by whichever bundle drew it. */
        public long usageOn(String bucketId) {
            return usageOn(null, bucketId);
        }

        /** As {@link #usageOn(String, String, String)}, for a caller holding one id per bucket. */
        public long usageOn(String serviceId, String bucketId) {
            return usageOn(serviceId, bucketId, null);
        }

        /**
         * Usage to report against a bucket of the bundle {@code serviceId} names.
         *
         * <p>A bucket id is the plan's name for the bucket rather than an id of its own, so a
         * subscriber on a recurring plan draws on the same bucket id cycle after cycle. Scoping
         * the total to the service instance being reported is what keeps the figure the usage of
         * <em>this</em> bundle rather than of every bundle the user has ever held.
         *
         * <p>Which of the two fallbacks a miss takes is the difference between a column that is
         * wrong and one that is merely unscoped, so they are kept apart:
         *
         * <ul>
         *   <li><b>The split names this bucket's bundles and not this one.</b> Then this bundle
         *       drew nothing from the bucket and <b>0</b> is the figure. Reporting the bucket's
         *       total across bundles here is what re-introduces the very thing
         *       {@code scope-to-service} exists to prevent — every cycle's usage reported against
         *       one cycle's bucket — and a report that is one row per bucket instance writes that
         *       same total onto every one of the subscriber's rows for that bucket id.</li>
         *   <li><b>There is no split to read.</b> The CDRs carry no {@code serviceId} the database
         *       recognises, or the aggregation had no room for every bundle, so nothing is known
         *       about who drew what: the bucket's total across bundles is reported, which is the
         *       same figure {@code scope-to-service: false} reports and a real number rather than
         *       a zero that would read as a subscriber who has used nothing.</li>
         * </ul>
         *
         * @param bucketInstanceId the bucket instance's own id, for a caller that holds one — see
         *                         {@link #bucketKey}. Null for a caller that does not
         */
        public long usageOn(String serviceId, String bucketId, String bucketInstanceId) {
            String key = bucketKey(bucketId, bucketInstanceId);
            if (!attributable || key == null || key.isEmpty()) {
                return total;
            }
            Long scoped = scopedUsage(serviceId, key);
            if (scoped != null) {
                return scoped;
            }
            if (serviceId != null && !serviceId.isEmpty() && splitBuckets.contains(key)) {
                return 0L;
            }
            Long forBucket = perBucket.get(key);
            return forBucket != null ? forBucket : 0L;
        }

        /**
         * Which of the two ids the CDR documents key this bucket's usage on.
         *
         * <p>cdr-service records a {@code bucketId} on each session instance, and a deployment can
         * have it be either BUCKET_INSTANCE.BUCKET_ID — the plan's bucket, which is the one the
         * dump has to ask by, since the bucket it reports is reached through the plan — or
         * BUCKET_INSTANCE.ID, the row's own id. Neither can be told from the other by looking at
         * the value, so the one the CDRs actually named wins: the plan's bucket where the user drew
         * on it, the instance's own id where they did not and the instance id is what the documents
         * carry. A user who drew on neither keeps the plan's bucket, so a miss still reports the 0
         * that says the CDRs never named this bucket.
         *
         * @return {@code bucketId} for a caller with no instance id, or where neither is named
         */
        public String bucketKey(String bucketId, String bucketInstanceId) {
            boolean named = bucketId != null && !bucketId.isEmpty();
            if (!attributable
                    || bucketInstanceId == null || bucketInstanceId.isEmpty()
                    || named && perBucket.containsKey(bucketId)) {
                return bucketId;
            }
            // The instance id is the key only where the CDRs named it, so that a bucket they named
            // under neither id still reports the 0 that says so — unless there is no plan bucket
            // to fall back to, where an id nothing was found under still beats no key at all: a
            // null key is what would hand this row the subscriber's whole usage.
            return !named || perBucket.containsKey(bucketInstanceId) ? bucketInstanceId : bucketId;
        }

        /** As {@link #knowsBucket(String, String)}, for a caller holding one id per bucket. */
        public boolean knowsBucket(String bucketId) {
            return knowsBucket(bucketId, null);
        }

        /**
         * Whether the CDR named this bucket among the ones this user drew on, under either of the
         * two ids a row can offer it.
         *
         * <p>False is what makes the reported usage a 0 — and a 0 that means "nothing is recorded
         * against this bucket", which is not the same thing as "nothing was drawn from it". The
         * two read identically in the file, so both reports count this: a run where no row's bucket
         * was one the CDR names is a run whose session instances are keyed by something other than
         * either id the database holds.
         *
         * <p>A user whose usage could not be split per bucket at all reports true, because the
         * figure they get is their whole total rather than a zero of that kind.
         */
        public boolean knowsBucket(String bucketId, String bucketInstanceId) {
            return !attributable || perBucket.containsKey(bucketKey(bucketId, bucketInstanceId));
        }

        /** As {@link #attributedTo(String, String, String)}, for a caller with one id per bucket. */
        public boolean attributedTo(String serviceId, String bucketId) {
            return attributedTo(serviceId, bucketId, null);
        }

        /**
         * Whether {@link #usageOn(String, String, String)} would report the named bundle's own
         * usage rather than one of the two fallbacks. Both reports count this: the fallback is
         * invisible in the file itself, and a run where it is what every row took is a run whose
         * CDRs do not key usage the way the database does.
         */
        public boolean attributedTo(String serviceId, String bucketId, String bucketInstanceId) {
            return scopedUsage(serviceId, bucketKey(bucketId, bucketInstanceId)) != null;
        }

        /** What the split records for one bundle against one bucket, or null where it records none. */
        private Long scopedUsage(String serviceId, String bucketKey) {
            if (!attributable
                    || serviceId == null || serviceId.isEmpty()
                    || bucketKey == null || bucketKey.isEmpty()) {
                return null;
            }
            return perServiceBucket.get(serviceBucketKey(serviceId, bucketKey));
        }
    }

    /** Next user in username order, or null at the end of the stream. */
    protected abstract UserUsage fetchNext();

    /**
     * The figures for {@code userName}, or null when the user has no session anywhere in the
     * indices that were aggregated. Callers must probe usernames in ascending UTF-8 order; a
     * username that has already been passed cannot be revisited.
     */
    public UserUsage forUser(String userName) {
        while (head == null || Utf8Order.compare(head.userName(), userName) < 0) {
            UserUsage next = fetchNext();
            if (next == null) {
                head = null;
                return null;
            }
            head = next;
        }

        if (!head.userName().equals(userName)) {
            return null;
        }

        UserUsage usage = head;
        // Nothing else in the dump reads this user again, so let the page entry go.
        head = null;
        return usage;
    }

    @Override
    public void close() {
        // Nothing to release by default; subclasses that hold resources override this.
    }

    /** A cursor with no figures at all, used when the Elasticsearch lookup is switched off. */
    public static UserUsageCursor empty() {
        return new UserUsageCursor() {
            @Override
            protected UserUsage fetchNext() {
                return null;
            }
        };
    }

    /** Shared empty map for users whose usage could not be split per bucket. */
    protected static Map<String, Long> noBuckets() {
        return Collections.emptyMap();
    }
}
