package com.axonect.ee.enterpriseintegration.domain.client;

import com.axonect.ee.enterpriseintegration.domain.util.Utf8Order;

/**
 * A forward-only stream of per-user CDR figures, ordered by username, that a caller walking users
 * in the same order can probe without ever holding more than one user's figures.
 *
 * <p>This is the half of the dump that keeps the Elasticsearch join off the heap. Materialising
 * every user's NAS address as a map would cost a full aggregation scan before the first CSV row
 * could be written; instead both sides are read in username order and merge-joined, so memory is
 * bounded by one Elasticsearch page regardless of user count and the two scans overlap.
 *
 * <p>Each user is handed out exactly once, so everything the dump takes from Elasticsearch has to
 * come out of that single probe — {@link #forUser(String)} returns the whole record, and a second
 * probe for the same username would find the stream already past it. There is one column left to
 * take: UTLIZED_QUOTA used to be read from here too, as a total summed from the usage deltas on
 * the session documents, and is now the quota bucket's own USAGE, off the dump's own cursor.
 */
public abstract class UserNasAddressCursor implements AutoCloseable {

    /** The current user's record, or null once the stream is exhausted. */
    private UserNasAddress head;

    /**
     * What the CDR session documents of the reported day say about one user.
     *
     * <p>Only the NAS address is a dump column. The username is what the merge join advances on,
     * and a record existing at all is what tells a subscriber who held no session that day — no
     * document under that username, so no record and an empty column — from one whose sessions
     * simply named no NAS.
     *
     * @param userName     the username the CDR documents were grouped under
     * @param nasIpAddress NAS the reported day's sessions were anchored to, null when none was
     *                     recorded
     */
    public record UserNasAddress(String userName, String nasIpAddress) {
    }

    /** Next user in username order, or null at the end of the stream. */
    protected abstract UserNasAddress fetchNext();

    /**
     * The record for {@code userName}, or null when the user has no session in the index that was
     * aggregated. Callers must probe usernames in ascending UTF-8 order; a username that has
     * already been passed cannot be revisited.
     */
    public UserNasAddress forUser(String userName) {
        while (head == null || Utf8Order.compare(head.userName(), userName) < 0) {
            UserNasAddress next = fetchNext();
            if (next == null) {
                head = null;
                return null;
            }
            head = next;
        }

        if (!head.userName().equals(userName)) {
            return null;
        }

        UserNasAddress current = head;
        // Nothing else in the dump reads this user again, so let the page entry go.
        head = null;
        return current;
    }

    @Override
    public void close() {
        // Nothing to release by default; subclasses that hold resources override this.
    }

    /** A cursor with no records at all, used when the Elasticsearch lookup is switched off. */
    public static UserNasAddressCursor empty() {
        return new UserNasAddressCursor() {
            @Override
            protected UserNasAddress fetchNext() {
                return null;
            }
        };
    }
}
