package com.plink.ticket.repository;

import java.util.Locale;

/** The console's search box, turned into a LIKE pattern that matches it literally. */
final class Search {
    private Search() {}

    /**
     * Lower-cased and wrapped for a substring match. A typed {@code %} or {@code _} is a
     * character somebody is looking for, not a wildcard, so both are escaped.
     */
    static String like(String typed) {
        String escaped = typed.trim().toLowerCase(Locale.ROOT)
            .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
