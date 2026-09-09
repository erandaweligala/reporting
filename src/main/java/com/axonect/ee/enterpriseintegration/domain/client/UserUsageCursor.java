package com.axonect.ee.enterpriseintegration.domain.client;

import com.axonect.ee.enterpriseintegration.domain.util.Utf8Order;

import java.util.Collections;
import java.util.Map;

/**
 * A forward-only stream of per-user daily CDR figures, ordered by username, that a caller walking
 * users in the same order can probe without ever holding more than one user's figures.
 *
 * <p>This is the half of the dump that keeps the Elasticsearch join off the heap. Materialising a
 * day for 3 million users as a map would cost hundreds of megabytes and a full aggregation scan
 * before the first CSV row could be written; instead both sides are read in username order and
 * merge-joined, so memory is bounded by one Elasticsearch page regardless of user count and the
 * two scans overlap.
 *
 * <p>Each user is handed out exactly once, so everything the dump takes from Elasticsearch — the
 * day's usage and the NAS the user's sessions were anchored to — has to come out of that single
 * probe. That is why {@link #forUser(String)} returns the whole record rather than one figure at a
 * time: a second probe for the same username would find the stream already past it.
 */
public abstract class UserUsageCursor implements AutoCloseable {

    /** Figures for the current user, or null once the stream is exhausted. */
    private UserUsage head;

    /**
     * A user's day: usage split per bucket where the mapping allows it, and the NAS IP address
     * their sessions reported.
     *
     * @param userName      the username the CDR documents were grouped under
     * @param perBucket     usage per bucket id, empty when the split is not trustworthy
     * @param total         the day's usage across every bucket
     * @param attributable  whether {@code perBucket} may be read
     * @param nasIpAddress  NAS the day's sessions were anchored to, null when none was recorded
     */
    public record UserUsage(String userName, Map<String, Long> perBucket, long total,
                            boolean attributable, String nasIpAddress) {

        /** Usage to report against {@code bucketId}, falling back to the day's total. */
        public long usageOn(String bucketId) {
            if (!attributable || bucketId == null || bucketId.isEmpty()) {
                return total;
            }
            Long forBucket = perBucket.get(bucketId);
            return forBucket != null ? forBucket : 0L;
        }
    }

    /** Next user in username order, or null at the end of the stream. */
    protected abstract UserUsage fetchNext();

    /**
     * The day's figures for {@code userName}, or null when the user has no session on the reported
     * day. Callers must probe usernames in ascending UTF-8 order; a username that has already been
     * passed cannot be revisited.
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
