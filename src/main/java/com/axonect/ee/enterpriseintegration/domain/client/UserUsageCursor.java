package com.axonect.ee.enterpriseintegration.domain.client;

import com.axonect.ee.enterpriseintegration.domain.util.Utf8Order;

import java.util.Collections;
import java.util.Map;

/**
 * A forward-only stream of per-user daily usage, ordered by username, that a caller walking users
 * in the same order can probe without ever holding more than one user's figures.
 *
 * <p>This is the half of the dump that keeps the usage join off the heap. Materialising a day of
 * usage for 3 million users as a map would cost hundreds of megabytes and a full aggregation scan
 * before the first CSV row could be written; instead both sides are read in username order and
 * merge-joined, so memory is bounded by one Elasticsearch page regardless of user count and the
 * two scans overlap.
 */
public abstract class UserUsageCursor implements AutoCloseable {

    /** Usage for the current user, or null once the stream is exhausted. */
    private UserUsage head;

    /** A user's usage for the day, split per bucket where the mapping allows it. */
    public record UserUsage(String userName, Map<String, Long> perBucket, long total, boolean attributable) {
    }

    /** Next user in username order, or null at the end of the stream. */
    protected abstract UserUsage fetchNext();

    /**
     * Usage to report for {@code userName} against {@code bucketId}, or null when the user has no
     * usage on the reported day. Callers must probe usernames in ascending UTF-8 order; a username
     * that has already been passed cannot be revisited.
     */
    public Long usageFor(String userName, String bucketId) {
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

        if (!usage.attributable() || bucketId == null || bucketId.isEmpty()) {
            return usage.total();
        }
        Long forBucket = usage.perBucket().get(bucketId);
        return forBucket != null ? forBucket : 0L;
    }

    @Override
    public void close() {
        // Nothing to release by default; subclasses that hold resources override this.
    }

    /** A cursor with no usage at all, used when the usage lookup is switched off. */
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
