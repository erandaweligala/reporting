package com.axonect.ee.enterpriseintegration.domain.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits the username keyspace into contiguous ranges that can be dumped independently.
 *
 * <p>Sharding on the username rather than on a row number is what makes the dump parallelisable at
 * all: the usage figures come from Elasticsearch keyed on username, so a shard can only aggregate
 * its own users if its boundary is expressible on both sides. A range predicate is — Oracle can
 * range-scan the username index for it and Elasticsearch can filter the same range on
 * {@code userName.keyword} — whereas a row offset or a hash is meaningless to Elasticsearch and
 * would force every shard to aggregate the whole day.
 *
 * <p>The outer shards are left open-ended so that no username can fall outside the plan, however
 * unusual its first character.
 */
public final class UserDumpShardPlanner {

    /**
     * Leading characters the boundaries are drawn from, in UTF-8 order. Real usernames are
     * alphanumeric; anything outside this set still lands in the first or last shard because those
     * are unbounded.
     */
    private static final String BOUNDARY_ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private UserDumpShardPlanner() {
    }

    /** Half-open username range {@code [fromInclusive, toExclusive)}; null means unbounded. */
    public record UsernameRange(String fromInclusive, String toExclusive) {

        public boolean isUnbounded() {
            return fromInclusive == null && toExclusive == null;
        }
    }

    /**
     * @param shards how many ranges to produce; values below 2 give a single unbounded range
     * @return the ranges in ascending order, together covering every possible username exactly once
     */
    public static List<UsernameRange> plan(int shards) {
        if (shards <= 1) {
            return List.of(new UsernameRange(null, null));
        }

        int effective = Math.min(shards, BOUNDARY_ALPHABET.length());
        List<UsernameRange> ranges = new ArrayList<>(effective);
        String previousBoundary = null;

        for (int i = 1; i < effective; i++) {
            String boundary = String.valueOf(
                    BOUNDARY_ALPHABET.charAt((int) ((long) i * BOUNDARY_ALPHABET.length() / effective)));
            ranges.add(new UsernameRange(previousBoundary, boundary));
            previousBoundary = boundary;
        }
        ranges.add(new UsernameRange(previousBoundary, null));

        return ranges;
    }
}
