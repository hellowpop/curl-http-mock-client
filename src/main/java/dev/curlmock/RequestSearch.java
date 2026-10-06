package dev.curlmock;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Shared literal token rules for filtering and highlighting. */
final class RequestSearch {
    private RequestSearch() {}

    static List<String> tokens(String query) {
        return Arrays.stream(query.toLowerCase(Locale.ROOT).split("(?U)[\\s,]+"))
                .filter(token -> !token.isEmpty()).distinct().toList();
    }
}
