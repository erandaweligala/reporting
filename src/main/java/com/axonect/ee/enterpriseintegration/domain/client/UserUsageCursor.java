package com.axonect.ee.enterpriseintegration.domain.client;

import com.axonect.ee.enterpriseintegration.domain.util.Utf8Order;

import java.util.Collections;
import java.util.Map;

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
     * their sessions on the reported day were anchored to.
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
     * @param total             usage across every bucket the user touched
     * @param attributable      whether {@code perBucket} may be read
     * @param nasIpAddress      NAS the reported day's sessions were anchored to, null when none
     *                          was recorded
     */
    public record UserUsage(String userName, Map<String, Long> perBucket,
                            Map<String, Long> perServiceBucket, long total,
                            boolean attributable, String nasIpAddress) {

        /** Separator between the two halves of a {@link #perServiceBucket} key. */
        private static final char KEY_SEPARATOR = '\u0000';

        /** How {@link #perServiceBucket} is keyed, so the client fills it the way this reads it. */
        public static String serviceBucketKey(String serviceId, String bucketId) {
            return serviceId + KEY_SEPARATOR + bucketId;
        }

        /** Usage to report against {@code bucketId} by whichever bundle drew it. */
        public long usageOn(String bucketId) {
            return usageOn(null, bucketId);
        }

        /**
         * Usage to report against {@code bucketId} of the bundle {@code serviceId} names.
         *
         * <p>A bucket id is the plan's name for the bucket rather than an id of its own, so a
         * subscriber on a recurring plan draws on the same bucket id cycle after cycle. Scoping
         * the total to the service instance the dump is reporting is what keeps UTLIZED_QUOTA the
         * usage of <em>this</em> bundle rather than of every bundle the user has ever held.
         *
         * <p>Falling back to the bucket's total across bundles, rather than to zero, is what a
         * CDR whose {@code serviceId} is not SERVICE_INSTANCE.ID degrades to: an unscoped total is
         * the same figure the dump would report with {@code scope-to-service} switched off, where
         * a zero would read as a subscriber who has used nothing at all.
         */
        public long usageOn(String serviceId, String bucketId) {
            if (!attributable || bucketId == null || bucketId.isEmpty()) {
                return total;
            }
            if (attributedTo(serviceId, bucketId)) {
                return perServiceBucket.get(serviceBucketKey(serviceId, bucketId));
            }
            Long forBucket = perBucket.get(bucketId);
            return forBucket != null ? forBucket : 0L;
        }

        /**
         * Whether the CDR named {@code bucketId} among the buckets this user drew on.
         *
         * <p>False is what makes UTLIZED_QUOTA a 0 — and a 0 that means "nothing is recorded
         * against this bucket", which is not the same thing as "nothing was drawn from it". The
         * two read identically in the file, so the dump counts this per shard as well: a shard
         * where no row's quota bucket was one the CDR names is a shard whose session instances
         * are keyed by something other than the BUCKET_ID the dump statement reads.
         *
         * <p>A user whose usage could not be split per bucket at all reports true, because the
         * figure they get is their whole total rather than a zero of that kind.
         */
        public boolean knowsBucket(String bucketId) {
            return !attributable || perBucket.containsKey(bucketId);
        }

        /**
         * Whether {@link #usageOn(String, String)} would report the named bundle's own usage
         * rather than the bucket's total across bundles. The dump counts this per shard: the
         * fallback is invisible in the file itself, and a shard where it is what every row took
         * is a shard whose CDRs do not key usage the way the dump assumes.
         */
        public boolean attributedTo(String serviceId, String bucketId) {
            return attributable
                    && serviceId != null && !serviceId.isEmpty()
                    && bucketId != null && !bucketId.isEmpty()
                    && perServiceBucket.containsKey(serviceBucketKey(serviceId, bucketId));
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
