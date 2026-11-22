package com.jujin.mjm.test;

/**
 * The little XML writer the test reports need: values are escaped, nothing is
 * pretty-printed, and the output is stable byte for byte.
 *
 * <p>commons ships a JSON writer only, so escaping lives here — attributes and
 * text have different rules, and a control character that XML forbids is
 * dropped rather than smuggled in.
 */
public final class Xml {

    private Xml() {}

    /** Text content: {@code & < >} escaped, tabs/newlines kept, other control chars dropped. */
    public static String text(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '\t', '\n', '\r' -> out.append(c);
                default -> {
                    if (c >= 0x20) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Attribute value: the text rules plus quotes and whitespace as character references. */
    public static String attribute(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                case '\n' -> out.append("&#10;");
                case '\r' -> out.append("&#13;");
                case '\t' -> out.append("&#9;");
                default -> {
                    if (c >= 0x20) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
