package com.axonect.ee.enterpriseintegration.domain.util;

import com.axonect.ee.enterpriseintegration.domain.util.UserDumpShardPlanner.UsernameRange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDumpShardPlannerTest {

    @Test
    void oneShardIsASingleUnboundedRange() {
        List<UsernameRange> ranges = UserDumpShardPlanner.plan(1);

        assertEquals(1, ranges.size());
        assertTrue(ranges.get(0).isUnbounded());
    }

    @Test
    void rangesAreContiguousAndCoverTheWholeKeyspace() {
        List<UsernameRange> ranges = UserDumpShardPlanner.plan(6);

        assertEquals(6, ranges.size());
        assertNull(ranges.get(0).fromInclusive(), "the first shard must catch anything below the alphabet");
        assertNull(ranges.get(ranges.size() - 1).toExclusive(), "the last shard must catch anything above it");

        for (int i = 1; i < ranges.size(); i++) {
            assertEquals(ranges.get(i - 1).toExclusive(), ranges.get(i).fromInclusive(),
                    "shard " + i + " must start where shard " + (i - 1) + " ended");
        }
    }

    @Test
    void boundariesAscendInUtf8Order() {
        List<UsernameRange> ranges = UserDumpShardPlanner.plan(8);

        for (int i = 1; i < ranges.size() - 1; i++) {
            String previous = ranges.get(i - 1).toExclusive();
            String current = ranges.get(i).toExclusive();
            assertNotNull(current);
            assertTrue(Utf8Order.compare(previous, current) < 0,
                    previous + " should sort before " + current);
        }
    }

    @Test
    void everyUsernameFallsInExactlyOneShard() {
        List<UsernameRange> ranges = UserDumpShardPlanner.plan(5);
        List<String> usernames = List.of("taiwowilliams", "0001", "ZZTop", "_system", "üser", "a");

        for (String username : usernames) {
            long matches = ranges.stream().filter(range -> contains(range, username)).count();
            assertEquals(1, matches, username + " landed in " + matches + " shards");
        }
    }

    @Test
    void neverPlansMoreShardsThanBoundariesAvailable() {
        assertTrue(UserDumpShardPlanner.plan(500).size() <= 62);
    }

    private static boolean contains(UsernameRange range, String username) {
        boolean aboveStart = range.fromInclusive() == null
                || Utf8Order.compare(username, range.fromInclusive()) >= 0;
        boolean belowEnd = range.toExclusive() == null
                || Utf8Order.compare(username, range.toExclusive()) < 0;
        return aboveStart && belowEnd;
    }
}
