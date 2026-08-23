package com.axonect.ee.enterpriseintegration.domain.util;

/**
 * Orders strings the way their UTF-8 bytes order.
 *
 * <p>The user data dump merge-joins an Oracle cursor ordered by USER_NAME against an
 * Elasticsearch composite aggregation keyed on the same username. Oracle's binary collation on an
 * AL32UTF8 database and Elasticsearch's keyword ordering both compare UTF-8 bytes, but
 * {@link String#compareTo} compares UTF-16 code units, which disagrees for any code point above
 * U+FFFF: those are stored as surrogates in the range U+D800..U+DFFF and so sort before
 * U+E000..U+FFFF in UTF-16 and after them in UTF-8. Nudging the two ranges past each other
 * restores UTF-8 order without materialising the bytes for every comparison — of which a
 * 3 million row dump does at least one per row.
 */
public final class Utf8Order {

    private static final int SURROGATE_START = 0xD800;
    private static final int SURROGATE_END = 0xE000;

    /** Lifts the surrogates (code points above U+FFFF) above the rest of the basic plane. */
    private static final int SURROGATE_SHIFT = 0x2000;

    /** Drops U+E000..U+FFFF into the gap the surrogates left behind. */
    private static final int PRIVATE_USE_SHIFT = 0x800;

    private Utf8Order() {
    }

    public static int compare(String left, String right) {
        int shared = Math.min(left.length(), right.length());
        for (int i = 0; i < shared; i++) {
            char a = left.charAt(i);
            char b = right.charAt(i);
            if (a != b) {
                return Integer.compare(rank(a), rank(b));
            }
        }
        return Integer.compare(left.length(), right.length());
    }

    /**
     * Where a UTF-16 code unit sits in code point order. Everything below U+D800 already agrees
     * with UTF-8; the two ranges above it are swapped so that a surrogate — which always stands for
     * a code point above U+FFFF — outranks every character that fits in the basic plane.
     */
    private static int rank(char unit) {
        if (unit < SURROGATE_START) {
            return unit;
        }
        return unit < SURROGATE_END ? unit + SURROGATE_SHIFT : unit - PRIVATE_USE_SHIFT;
    }
}
