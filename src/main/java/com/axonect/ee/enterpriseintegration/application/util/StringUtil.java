package com.axonect.ee.enterpriseintegration.application.util;

public final class StringUtil {

    private StringUtil() {
        // prevent instantiation
    }

    public static String toSnakeCase(String camelCase) {
        if (camelCase == null) return null;
        return camelCase
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toLowerCase();
    }
}
