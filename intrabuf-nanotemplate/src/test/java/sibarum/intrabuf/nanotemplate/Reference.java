package sibarum.intrabuf.nanotemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nanotemplate as docs/nanotemplate.md specifies it, written for clarity rather than length: what every one-line
 * filler is held to.
 */
final class Reference {

    private static final Pattern HEADER = Pattern.compile("nanotemplate 1 ([!-~]) ([^ ]+) ([^ ]+)");

    private Reference() {
    }

    /** Why a template was refused. */
    enum Refusal { NOT_A_TEMPLATE, NO_VALUE }

    static final class Refused extends RuntimeException {
        final Refusal why;

        Refused(Refusal why, String detail) {
            super(why + ": " + detail);
            this.why = why;
        }
    }

    /** The body of {@code file} with every placeholder replaced by its value from {@code pairs} (key, value, ...). */
    static byte[] fill(byte[] file, List<String> pairs) {
        String text = new String(file, StandardCharsets.UTF_8);
        int lf = text.indexOf('\n');
        if (lf < 0) {
            throw new Refused(Refusal.NOT_A_TEMPLATE, "no line feed after the header");
        }
        String header = text.substring(0, lf);
        if (header.endsWith("\r")) {
            header = header.substring(0, header.length() - 1);
        }
        Matcher m = HEADER.matcher(header);
        if (!m.matches()) {
            throw new Refused(Refusal.NOT_A_TEMPLATE, "header " + header);
        }
        String esc = m.group(1), open = m.group(2), close = m.group(3);
        if (open.contains(esc) || close.contains(esc)) {
            throw new Refused(Refusal.NOT_A_TEMPLATE, "the escape is inside a delimiter");
        }
        String body = text.substring(lf + 1);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < body.length()) {
            if (body.startsWith(esc, i)) {
                i = escaped(body, i, out);
            } else if (body.startsWith(open, i)) {
                StringBuilder key = new StringBuilder();
                i += open.length();
                while (!body.startsWith(close, i)) {
                    if (i >= body.length()) {
                        throw new Refused(Refusal.NOT_A_TEMPLATE, "unclosed placeholder");
                    }
                    i = body.startsWith(esc, i) ? escaped(body, i, key) : append(body, i, key);
                }
                i += close.length();
                out.append(value(key.toString(), pairs));
            } else {
                i = append(body, i, out);
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The character after the escape at {@code i}, whatever it is. */
    private static int escaped(String s, int i, StringBuilder to) {
        if (i + 1 >= s.length()) {
            throw new Refused(Refusal.NOT_A_TEMPLATE, "escape at the end");
        }
        return append(s, i + 1, to);
    }

    /** One code point, so an escape never splits a surrogate pair. */
    private static int append(String s, int i, StringBuilder to) {
        int n = Character.charCount(s.codePointAt(i));
        to.append(s, i, i + n);
        return i + n;
    }

    /** The value of the first pair whose key is exactly {@code key}; a trailing key with no value is not a pair. */
    private static String value(String key, List<String> pairs) {
        for (int i = 0; i + 1 < pairs.size(); i += 2) {
            if (pairs.get(i).equals(key)) {
                return pairs.get(i + 1);
            }
        }
        throw new Refused(Refusal.NO_VALUE, key);
    }
}
