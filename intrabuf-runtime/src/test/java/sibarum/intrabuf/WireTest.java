package sibarum.intrabuf;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void bitsetWordsReadZeroPastTheirEnd() {
        IbReader r = new IbReader(new byte[]{(byte) 0xFF, 0x01, 0x7F});
        int at = r.beginBits(2);
        assertEquals(0x01FFL, r.bitsWord(at, 2, 0));
        assertEquals(0L, r.bitsWord(at, 2, 1));
        assertEquals(1, r.remaining());
    }
}
