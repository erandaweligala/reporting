package com.axonect.ee.enterpriseintegration.domain.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserNasAddressCursorTest {

    /** A cursor over a fixed, username-ordered list, standing in for the aggregation pages. */
    private static UserNasAddressCursor cursorOver(List<UserNasAddressCursor.UserNasAddress> entries) {
        Deque<UserNasAddressCursor.UserNasAddress> queue = new ArrayDeque<>(entries);
        return new UserNasAddressCursor() {
            @Override
            protected UserNasAddress fetchNext() {
                return queue.poll();
            }
        };
    }

    private static UserNasAddressCursor.UserNasAddress session(String user, String nasIp) {
        return new UserNasAddressCursor.UserNasAddress(user, nasIp);
    }

    /** The address the cursor reports for one user, or null when it has no record of them. */
    private static String addressFor(UserNasAddressCursor cursor, String user) {
        UserNasAddressCursor.UserNasAddress session = cursor.forUser(user);
        return session == null ? null : session.nasIpAddress();
    }

    @Test
    void carriesTheNasAddressTheDumpHasNoDatabaseColumnFor() {
        UserNasAddressCursor cursor = cursorOver(List.of(session("alice", "10.20.30.40")));

        assertEquals("10.20.30.40", addressFor(cursor, "alice"));
    }

    @Test
    void aUserWhoseSessionsReportedNoNasAddressIsStillAUserWhoHadSessions() {
        // The record existing is what says the aggregation returned them: the column is empty
        // either way, and only the shard log's count of missing users tells the two apart.
        UserNasAddressCursor cursor = cursorOver(List.of(session("alice", null)));

        UserNasAddressCursor.UserNasAddress alice = cursor.forUser("alice");

        assertNotNull(alice, "alice held a session that day, it simply named no NAS");
        assertNull(alice.nasIpAddress());
    }

    @Test
    void skipsPastUsersTheDumpNoLongerCaresAbout() {
        UserNasAddressCursor cursor = cursorOver(List.of(
                session("alice", "10.0.0.1"),
                session("bob", "10.0.0.2"),
                session("carol", "10.0.0.3")));

        // The dump walks users in the same order but has no row for bob.
        assertEquals("10.0.0.1", addressFor(cursor, "alice"));
        assertEquals("10.0.0.3", addressFor(cursor, "carol"));
    }

    @Test
    void usersWithNoSessionsAtAllReportNothing() {
        UserNasAddressCursor cursor = cursorOver(List.of(session("carol", "10.0.0.3")));

        assertNull(cursor.forUser("alice"), "alice has no session document at all");
        assertEquals("10.0.0.3", addressFor(cursor, "carol"));
    }

    @Test
    void keepsReportingNothingOnceTheStreamRunsOut() {
        UserNasAddressCursor cursor = cursorOver(List.of(session("alice", "10.0.0.1")));

        assertEquals("10.0.0.1", addressFor(cursor, "alice"));
        assertNull(cursor.forUser("bob"));
        assertNull(cursor.forUser("carol"));
    }

    @Test
    void handsEachUserOutOnlyOnce() {
        // The dump probes once per user for exactly this reason: the stream is forward-only, and
        // a second probe for a username it has already handed out finds it gone.
        UserNasAddressCursor cursor = cursorOver(List.of(session("alice", "10.0.0.1")));

        assertEquals("10.0.0.1", addressFor(cursor, "alice"));
        assertNull(cursor.forUser("alice"));
    }

    @Test
    void theEmptyCursorNeverMatches() {
        assertNull(UserNasAddressCursor.empty().forUser("alice"));
    }
}
