package com.jujin.jeno.resolve;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version ordering and major-version extraction — SPEC.md §2.4's two version
 * regimes in one place. Central versions sort by a faithful subset of Maven's
 * {@code ComparableVersion} semantics: tokenize on {@code .} and {@code -},
 * numeric tokens compare as numbers and outrank strings, known qualifiers sit
 * on a fixed ladder ({@code alpha < beta < milestone < rc = cr < snapshot <
 * release < sp}, with {@code ga}/{@code final} equal to the release), unknown
 * strings sort between the pre-release qualifiers and the release, and absent
 * tokens pad as zero. jeno-native versions are semver, enforced where they
 * enter (tags, overrides).
 *
 * <p>{@code majorOf} is the best-effort half of the cross-major rule: a
 * Central version whose leading run is digits yields its major
 * ({@code 2023.01} → 2023, {@code 30.0-jre} → 30); anything else yields
 * null, and that group falls back to plain max-wins.
 */
public final class Versions {

    /** jeno-native semver: {@code X.Y.Z} with an optional {@code -prerelease}. */
    private static final Pattern NATIVE_SEMVER =
            Pattern.compile("\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.]+)?");

    private static final Pattern LEADING_MAJOR = Pattern.compile("^(\\d+)");

    /** Known qualifiers in Maven's order; the release boundary sits at zero. */
    private static final Map<String, Integer> QUALIFIERS = Map.of(
            "alpha", -5,
            "beta", -4,
            "milestone", -3,
            "rc", -2,
            "cr", -2,
            "snapshot", -1,
            "", 0,
            "ga", 0,
            "final", 0,
            "sp", 1);

    /** Unknown strings live between the pre-release qualifiers and the release. */
    private static final double TEXT_ORDINAL = -0.5;

    private Versions() {}

    /** The major of a Central version, or null when none can be extracted. */
    public static Integer majorOf(String version) {
        Matcher matcher = LEADING_MAJOR.matcher(version);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whether a version is the semver shape jeno-native releases must carry. */
    public static boolean isNativeSemver(String version) {
        return NATIVE_SEMVER.matcher(version).matches();
    }

    /** Maven-semantics ordering for two Central version strings. */
    public static int compare(String left, String right) {
        List<Item> a = parse(left);
        List<Item> b = parse(right);
        int width = Math.max(a.size(), b.size());
        for (int i = 0; i < width; i++) {
            Item ia = i < a.size() ? a.get(i) : null;
            Item ib = i < b.size() ? b.get(i) : null;
            int compared = compare(ia, ib);
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // the item model — number / qualifier / text, with absent padding
    // ------------------------------------------------------------------

    private sealed interface Item permits NumberItem, QualifierItem, TextItem {
    }

    private record NumberItem(BigInteger value) implements Item {
    }

    /** A known qualifier; the ordinal fixes Maven's order, release = 0. */
    private record QualifierItem(int ordinal) implements Item {
    }

    private record TextItem(String value) implements Item {
    }

    private static int compare(Item a, Item b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null || b == null) {
            // The absent side pads as zero: "1.0" == "1.0.0". Against the
            // release boundary it ranks by the qualifier's ladder position
            // ("1.0-rc" < "1.0" < "1.0-sp"), and it outranks unknown text.
            Item present = a != null ? a : b;
            if (present instanceof NumberItem) {
                return 0;
            }
            int rank = present instanceof QualifierItem qualifier
                    ? Integer.compare(qualifier.ordinal(), 0)
                    : -1;
            return a != null ? rank : -rank;
        }
        if (a instanceof NumberItem numberA && b instanceof NumberItem numberB) {
            return numberA.value().compareTo(numberB.value());
        }
        if (a instanceof NumberItem) {
            return 1;  // a number outranks any string at the same position
        }
        if (b instanceof NumberItem) {
            return -1;
        }
        if (a instanceof TextItem textA && b instanceof TextItem textB) {
            return textA.value().compareTo(textB.value());
        }
        double ordinalA = ordinalOf(a);
        double ordinalB = ordinalOf(b);
        if (ordinalA != ordinalB) {
            return Double.compare(ordinalA, ordinalB);
        }
        return 0;  // equal ladders (rc vs cr, ga vs final)
    }

    private static double ordinalOf(Item item) {
        return item instanceof QualifierItem qualifier
                ? qualifier.ordinal()
                : TEXT_ORDINAL;
    }

    private static List<Item> parse(String version) {
        List<Item> items = new ArrayList<>();
        int index = 0;
        while (index < version.length()) {
            char c = version.charAt(index);
            if (c >= '0' && c <= '9') {
                int start = index;
                while (index < version.length() && version.charAt(index) >= '0'
                        && version.charAt(index) <= '9') {
                    index++;
                }
                items.add(new NumberItem(new BigInteger(version.substring(start, index))));
                continue;
            }
            if (c == '.' || c == '-') {
                index++;  // separator — a boundary, not a token
                continue;
            }
            int start = index;
            while (index < version.length()) {
                char cc = version.charAt(index);
                if ((cc >= '0' && cc <= '9') || cc == '.' || cc == '-') {
                    break;
                }
                index++;
            }
            String token = version.substring(start, index).toLowerCase();
            Integer qualifier = QUALIFIERS.get(token);
            items.add(qualifier != null ? new QualifierItem(qualifier) : new TextItem(token));
            // letters may flow straight into digits ("alpha1"): the digit run
            // becomes its own token on the next loop iteration
        }
        return items;
    }
}
