package com.axonect.ee.enterpriseintegration.domain.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Utf8OrderTest {

    @Test
    void ordersAsciiTheObviousWay() {
        assertTrue(Utf8Order.compare("aaa", "aab") < 0);
        assertTrue(Utf8Order.compare("b", "a") > 0);
        assertEquals(0, Utf8Order.compare("taiwowilliams", "taiwowilliams"));
    }

    @Test
    void shorterPrefixSortsFirst() {
        assertTrue(Utf8Order.compare("user", "user1") < 0);
    }

    @Test
    void agreesWithUtf8BytesWhereTheJdkOrderingDoesNot() {
        // U+10000 is stored as a surrogate pair, which UTF-16 sorts before U+FFFD but UTF-8 sorts
        // after it. Elasticsearch and an AL32UTF8 Oracle both use the UTF-8 order, so a merge-join
        // keyed on String.compareTo would drift apart here.
        String supplementary = new String(Character.toChars(0x10000));
        String highBmp = "\uFFFD";

        assertTrue(byteOrder(highBmp, supplementary) < 0, "premise: UTF-8 puts U+FFFD first");
        assertTrue(highBmp.compareTo(supplementary) > 0, "premise: the JDK puts it second");
        assertTrue(Utf8Order.compare(highBmp, supplementary) < 0);
    }

    @Test
    void matchesByteOrderAcrossAMixedSample() {
        List<String> sample = Arrays.asList(
                "Zeta", "alpha", "Alpha", "zeta", "a1", "a", "9x", "_x", "\u00FCmlaut");

        for (String left : sample) {
            for (String right : sample) {
                assertEquals(Integer.signum(byteOrder(left, right)),
                        Integer.signum(Utf8Order.compare(left, right)),
                        "disagreed on " + left + " vs " + right);
            }
        }
    }

    private static int byteOrder(String left, String right) {
        return Arrays.compareUnsigned(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
