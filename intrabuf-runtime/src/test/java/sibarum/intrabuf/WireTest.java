package sibarum.intrabuf;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireTest {

    @Test
    void varintsAndZigzag() {
        int[] ints = {0, 1, -1, 63, -64, 64, -65, 127, 128, 8191, 8192, Integer.MAX_VALUE, Integer.MIN_VALUE};
        long[] longs = {0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, 1L << 35, -(1L << 49)};
        IbWriter w = new IbWriter(1);
        for (int v : ints) {
            w.writeInt32(v);
            w.writeVarint(v);
        }
        for (long v : longs) {
            w.writeInt64(v);
            w.writeVarLong(v);
        }
        IbReader r = new IbReader(w.toByteArray());
        for (int v : ints) {
            assertEquals(v, r.readInt32());
            assertEquals(v, r.readVarint());
        }
        for (long v : longs) {
            assertEquals(v, r.readInt64());
            assertEquals(v, r.readVarLong());
        }
        assertEquals(0, r.remaining());
    }

    @Test
    void smallValuesAreOneByte() {
        IbWriter w = new IbWriter();
        w.writeInt32(-64);
        w.writeInt32(63);
        assertEquals(2, w.position());
    }

    @Test
    void overlongVarintsAreRejected() {
        assertThrows(IntrabufException.class, () -> new IbReader(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x1F}).readVarint());
        assertThrows(IntrabufException.class, () -> new IbReader(new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0}).readVarint());
        assertThrows(IntrabufException.class, () -> new IbReader(new byte[]{(byte) 0x80}).readVarint());
    }

    @Test
    void lengthPrefixShiftsLongBodies() {
        for (int len : new int[]{0, 1, 127, 128, 129, 16383, 16384, 2_100_000}) {
            IbWriter w = new IbWriter(4);
            w.writeInt8((byte) 0x55);
            int mark = w.beginLength();
            for (int i = 0; i < len; i++) {
                w.writeInt8((byte) i);
            }
            w.endLength(mark);
            w.writeInt8((byte) 0x66);
            IbReader r = new IbReader(w.toByteArray());
            assertEquals(0x55, r.readByte());
            assertEquals(len, r.readCount());
            for (int i = 0; i < len; i++) {
                if (r.readByte() != (byte) i) {
                    throw new AssertionError("byte " + i + " of " + len);
                }
            }
            assertEquals(0x66, r.readByte());
            assertEquals(0, r.remaining());
        }
    }

    @Test
    void fixedFloatsAndObjects() {
        IbWriter w = new IbWriter();
        w.writeFixed32(0x01020304);
        w.writeFloat64(-0.0);
        w.writeFloat32(Float.NaN);
        w.writeUuid(new UUID(1, 2));
        w.writeInstant(Instant.ofEpochSecond(-5, 999_999_999));
        w.writeString("");
        w.writeBytes(new byte[]{1, 2});
        w.writeChar('￿');
        w.writeInt16(Short.MIN_VALUE);
        byte[] b = w.toByteArray();
        assertArrayEquals(new byte[]{4, 3, 2, 1}, java.util.Arrays.copyOf(b, 4), "little-endian");
        IbReader r = new IbReader(b);
        assertEquals(0x01020304, r.readFixed32());
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(r.readFloat64()));
        assertEquals(Float.floatToRawIntBits(Float.NaN), Float.floatToRawIntBits(r.readFloat32()));
        assertEquals(new UUID(1, 2), r.readUuid());
        assertEquals(Instant.ofEpochSecond(-5, 999_999_999), r.readInstant());
        assertEquals("", r.readString());
        assertArrayEquals(new byte[]{1, 2}, r.readBytes());
        assertEquals('￿', r.readChar());
        assertEquals(Short.MIN_VALUE, r.readInt16());
    }

    @Test
    void invalidValuesAreRejected() {
        assertThrows(IntrabufException.class, () -> new IbReader(new byte[]{2}).readBool());
        assertThrows(IntrabufException.class, () -> new IbReader(new byte[]{(byte) 0x80, (byte) 0x80, 0x08}).readChar());
        assertThrows(IntrabufException.class, () -> new IbReader(new byte[]{5}).readEnum(3, "E"));
        // 2 seconds + 1e9 nanos is not a valid Instant encoding.
        IbWriter w = new IbWriter();
        w.writeInt64(2);
        w.writeVarint(1_000_000_000);
        assertThrows(IntrabufException.class, () -> new IbReader(w.toByteArray()).readInstant());
    }

    @Test
    void readerSlicesAreBounded() {
        byte[] b = {1, 2, 3, 4, 5};
        IbReader r = new IbReader(b, 1, 2);
        assertEquals(2, r.readByte());
        assertEquals(3, r.readByte());
        assertThrows(IntrabufException.class, r::readByte);
        assertThrows(IntrabufException.class, () -> new IbReader(b, 0, 3).readFixed32());
    }

    private static String roundTrip(String s) {
        IbWriter w = new IbWriter(1);
        w.writeString(s);
        w.writeInt8((byte) 0x66);
        IbReader r = new IbReader(w.toByteArray());
        String back = r.readString();
        assertEquals(0x66, r.readByte());
        assertEquals(0, r.remaining());
        return back;
    }

    private static byte[] encodedString(String s) {
        IbWriter w = new IbWriter();
        w.writeString(s);
        IbReader r = new IbReader(w.toByteArray());
        int len = r.readCount();
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) {
            b[i] = r.readByte();
        }
        assertEquals(0, r.remaining());
        return b;
    }

    @Test
    void wellFormedStringsAreExactlyUtf8() {
        // The hand-written encoder must not change a single byte of what getBytes wrote before it.
        Random rnd = new Random(20261019);
        int[] cps = {'a', 0x7F, 0x80, 0x7FF, 0x800, 0xD7FF, 0xE000, 0xFFFD, 0xFFFF, 0x10000, 0x1F600, 0x10FFFF};
        for (int iter = 0; iter < 2000; iter++) {
            StringBuilder sb = new StringBuilder();
            for (int n = rnd.nextInt(iter < 1000 ? 10 : 300); n > 0; n--) {
                sb.appendCodePoint(rnd.nextBoolean() ? cps[rnd.nextInt(cps.length)] : rnd.nextInt(0x110000) & ~0x800);
            }
            String s = sb.toString();
            assertArrayEquals(s.getBytes(StandardCharsets.UTF_8), encodedString(s), s);
            assertEquals(s, roundTrip(s));
        }
    }

    @Test
    void unpairedSurrogatesRoundTrip() {
        String[] strings = {"\uD800", "\uDC00", "\uDBFF\uDBFF", "\uDC00\uD800", "a\uD83D", "\uDE00b", "😀\uD83D",
                "x".repeat(200) + "\uD800", "é\uDFFF" + "😀".repeat(100), "�", "�\uD800�"};
        for (String s : strings) {
            assertEquals(s, roundTrip(s), s);
        }
        // The three bytes of the code unit, as generalized UTF-8 (WTF-8) writes it.
        assertArrayEquals(new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80}, encodedString("\uD800"));
        assertArrayEquals(new byte[]{'a', (byte) 0xED, (byte) 0xBF, (byte) 0xBF}, encodedString("a\uDFFF"));
        // A pair is one code point, whichever surrogates sit around it.
        assertArrayEquals(new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0xBD, (byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80},
                encodedString("\uD83D😀"));
    }

    @Test
    void randomCodeUnitsRoundTrip() {
        Random rnd = new Random(7);
        for (int iter = 0; iter < 5000; iter++) {
            char[] cs = new char[rnd.nextInt(iter < 4000 ? 8 : 400)];
            for (int i = 0; i < cs.length; i++) {
                // Mostly surrogates, so unpaired ones and pairs both turn up often.
                cs[i] = rnd.nextInt(3) == 0 ? (char) rnd.nextInt(0x10000) : (char) (0xD800 + rnd.nextInt(0x800));
            }
            String s = new String(cs);
            assertEquals(s, roundTrip(s));
        }
    }

    @Test
    void malformedUtf8IsRejected() {
        byte[][] bad = {
                {(byte) 0x80},                                     // a continuation with no lead
                {(byte) 0xC0, (byte) 0x80},                        // overlong NUL
                {(byte) 0xE0, (byte) 0x80, (byte) 0x80},           // overlong
                {(byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0x80},
                {(byte) 0xE2, (byte) 0x82},                        // truncated
                {'a', (byte) 0xC3},
                {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, // past U+10FFFF
                {(byte) 0xF5, (byte) 0x80, (byte) 0x80, (byte) 0x80},
                {(byte) 0xFF},
                {(byte) 0xC3, 'a'},
                // A pair as two halves: no writer produces it, since it would read back as the pair.
                {(byte) 0xED, (byte) 0xA0, (byte) 0xBD, (byte) 0xED, (byte) 0xB8, (byte) 0x80},
        };
        for (byte[] b : bad) {
            IbWriter w = new IbWriter();
            w.writeBytes(b); // a length and the bytes: the framing of a string
            IntrabufException e = assertThrows(IntrabufException.class, () -> new IbReader(w.toByteArray()).readString(),
                    java.util.Arrays.toString(b));
            assertTrue(e.getMessage().contains("invalid UTF-8"), e.getMessage());
        }
        // A replacement character that was really written is not a sign of anything wrong.
        assertEquals("a�b", roundTrip("a�b"));
    }

    @Test
    void bitsetWordsReadZeroPastTheirEnd() {
        IbReader r = new IbReader(new byte[]{(byte) 0xFF, 0x01, 0x7F});
        int at = r.beginBits(2);
        assertEquals(0x01FFL, r.bitsWord(at, 2, 0));
        assertEquals(0L, r.bitsWord(at, 2, 1));
        assertEquals(1, r.remaining());
    }
}
