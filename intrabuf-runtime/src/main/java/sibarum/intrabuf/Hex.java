package sibarum.intrabuf;

import java.util.HexFormat;

/** Hex formatting for ids and fingerprints, which humans compare by eye in manifests and CLI output. */
public final class Hex {

    private Hex() {
    }

    public static String int32(int v) {
        return "0x" + HexFormat.of().toHexDigits(v);
    }

    public static int parseInt32(String s) {
        String t = s.startsWith("0x") || s.startsWith("0X") ? s.substring(2) : s;
        return Integer.parseUnsignedInt(t, 16);
    }

    public static String bytes(byte[] b, int off, int len) {
        return HexFormat.ofDelimiter(" ").formatHex(b, off, off + len);
    }
}
