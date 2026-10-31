package edu.skope.domain;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Campus facilities housed in a named building, from the university's official library guide
 * (skuniv.ac.kr/library: the 학술정보관 occupies 유담관 7-9층). Used for the analysis, follow-up merging and
 * similar-report place comparison alike. The mapping is one way: a building name never implies a facility,
 * and stored rows and vectors are not rewritten.
 */
public final class CampusPlaces {
    private CampusPlaces() { }
    private static final Pattern LIBRARY = Pattern.compile("(?:서경대(?:학교)?)?(?:중앙)?(?:도서관|학술정보관)");
    private static final Map<Pattern,String> HOUSED = Map.of(LIBRARY, "유담관");

    /** The housing building for a facility name used as a building, otherwise the value unchanged. */
    public static String building(String value) {
        if (value == null) return null;
        String compact = value.replaceAll("\\s+", "");
        for (var entry : HOUSED.entrySet()) if (entry.getKey().matcher(compact).matches()) return entry.getValue();
        return value;
    }

    /** Whether the value names a facility housed in a building (so it is context, not a separate building). */
    public static boolean housedFacility(String value) {
        return value != null && !Objects.equals(building(value), value);
    }

    /** Comparison key for building equality: whitespace/case-insensitive and facility-aware. */
    public static String key(Object value) {
        String text = Objects.toString(value, "");
        return building(text.strip()).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
