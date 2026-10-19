package sibarum.intrabuf;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/**
 * A growable byte buffer with the primitive encodings of the wire format. Generated codecs call it directly; it is
 * public so callers can reuse one across encodes ({@link #reset()}) instead of allocating per message.
 *
 * <p>Integers are zigzag LEB128 varints unless declared fixed; floats and fixed integers are little-endian. Every
 * write method is named for the {@link sibarum.intrabuf.schema.ScalarKind} it encodes, which is what lets the
 * processor generate {@code w.write<Kind>(x)} without a lookup table.
 */
public final class IbWriter {

    private static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private byte[] buf;
    private int pos;

    public IbWriter() {
        this(64);
    }

    public IbWriter(int initialCapacity) {
        buf = new byte[Math.max(16, initialCapacity)];
    }

    public int position() {
        return pos;
    }

    /** Discards everything written, keeping the buffer. */
    public IbWriter reset() {
        pos = 0;
        return this;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, pos);
    }

    /** The backing array; valid bytes are {@code [0, position())}. Changes on the next write that grows it. */
    public byte[] array() {
        return buf;
    }

    private void ensure(int n) {
        if (pos + n > buf.length) {
            grow(n);
        }
    }

    private void grow(int n) {
        long want = Math.max((long) buf.length << 1, (long) pos + n);
        if (want > Integer.MAX_VALUE - 8) {
            if ((long) pos + n > Integer.MAX_VALUE - 8) {
                throw new IntrabufException("payload exceeds the maximum array size");
            }
            want = Integer.MAX_VALUE - 8;
        }
        buf = Arrays.copyOf(buf, (int) want);
    }

    // ---- raw ---------------------------------------------------------------------------------------------------

    public void writeRaw(byte[] b) {
        writeRaw(b, 0, b.length);
    }

    public void writeRaw(byte[] b, int off, int len) {
        ensure(len);
        System.arraycopy(b, off, buf, pos, len);
        pos += len;
    }

    /** Unsigned LEB128. Used for lengths, counts, ordinals and versions. */
    public void writeVarint(int v) {
        ensure(5);
        while ((v & ~0x7F) != 0) {
            buf[pos++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf[pos++] = (byte) v;
    }

    public void writeVarLong(long v) {
        ensure(10);
        while ((v & ~0x7FL) != 0) {
            buf[pos++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf[pos++] = (byte) v;
    }

    /** The low {@code nbytes} bytes of {@code word}, little-endian: one word of a message's bitset. */
    public void writeBits(long word, int nbytes) {
        ensure(nbytes);
        for (int i = 0; i < nbytes; i++) {
            buf[pos++] = (byte) (word >>> (i << 3));
        }
    }

    /**
     * Reserves a one-byte length prefix and returns a mark for {@link #endLength}. The common case (a body under 128
     * bytes) costs nothing more; a longer body is shifted right to make room for the extra varint bytes.
     */
    public int beginLength() {
        ensure(1);
        return pos++;
    }

    public void endLength(int mark) {
        int len = pos - mark - 1;
        int extra = varintSize(len) - 1;
        if (extra > 0) {
            ensure(extra);
            System.arraycopy(buf, mark + 1, buf, mark + 1 + extra, len);
            pos += extra;
        }
        int p = mark;
        int v = len;
        while ((v & ~0x7F) != 0) {
            buf[p++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf[p] = (byte) v;
    }

    static int varintSize(int v) {
        int n = 1;
        while ((v & ~0x7F) != 0) {
            n++;
            v >>>= 7;
        }
        return n;
    }

    // ---- scalar kinds ------------------------------------------------------------------------------------------

    public void writeBool(boolean v) {
        ensure(1);
        buf[pos++] = (byte) (v ? 1 : 0);
    }

    public void writeInt8(byte v) {
        ensure(1);
        buf[pos++] = v;
    }

    public void writeInt16(short v) {
        writeVarint((v << 1) ^ (v >> 31));
    }

    public void writeInt32(int v) {
        writeVarint((v << 1) ^ (v >> 31));
    }

    public void writeInt64(long v) {
        writeVarLong((v << 1) ^ (v >> 63));
    }

    public void writeFixed32(int v) {
        ensure(4);
        INT_LE.set(buf, pos, v);
        pos += 4;
    }

    public void writeFixed64(long v) {
        ensure(8);
        LONG_LE.set(buf, pos, v);
        pos += 8;
    }

    public void writeFloat32(float v) {
        writeFixed32(Float.floatToRawIntBits(v));
    }

    public void writeFloat64(double v) {
        writeFixed64(Double.doubleToRawLongBits(v));
    }

    public void writeChar(char v) {
        writeVarint(v);
    }

    public void writeString(String s) {
        int len = s.length();
        // Fast path: pure ASCII encodes 1:1, so write the length and copy chars without allocating.
        if (len < 128) {
            ensure(1 + len);
            int start = pos + 1;
            int i = 0;
            for (; i < len; i++) {
                char c = s.charAt(i);
                if (c >= 0x80) {
                    break;
                }
                buf[start + i] = (byte) c;
            }
            if (i == len) {
                buf[pos] = (byte) len;
                pos = start + len;
                return;
            }
        }
        // Encoded by hand rather than with getBytes, which would turn an unpaired surrogate into '?': a Java string
        // is UTF-16 code units, and every one of them comes back. See utf8Length.
        int n = utf8Length(s);
        writeVarint(n);
        ensure(n);
        int p = pos;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                buf[p++] = (byte) c;
            } else if (c < 0x800) {
                buf[p++] = (byte) (0xC0 | (c >>> 6));
                buf[p++] = (byte) (0x80 | (c & 0x3F));
            } else if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                int cp = Character.toCodePoint(c, s.charAt(++i));
                buf[p++] = (byte) (0xF0 | (cp >>> 18));
                buf[p++] = (byte) (0x80 | ((cp >>> 12) & 0x3F));
                buf[p++] = (byte) (0x80 | ((cp >>> 6) & 0x3F));
                buf[p++] = (byte) (0x80 | (cp & 0x3F));
            } else {
                // Includes an unpaired surrogate, as the three bytes its code unit would have.
                buf[p++] = (byte) (0xE0 | (c >>> 12));
                buf[p++] = (byte) (0x80 | ((c >>> 6) & 0x3F));
                buf[p++] = (byte) (0x80 | (c & 0x3F));
            }
        }
        pos = p;
    }

    /**
     * The encoded length of {@code s}: UTF-8, generalized (as WTF-8 is) so that an unpaired surrogate, which UTF-8
     * cannot carry, takes the three bytes of its code unit. A string without one encodes exactly as UTF-8.
     */
    static int utf8Length(String s) {
        int len = s.length();
        long n = len;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= 0x80) {
                if (c < 0x800) {
                    n += 1;
                } else if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                    n += 2; // four bytes for two chars
                    i++;
                } else {
                    n += 2;
                }
            }
        }
        if (n > Integer.MAX_VALUE - 8) {
            throw new IntrabufException("string of " + len + " chars exceeds the maximum array size when encoded");
        }
        return (int) n;
    }

    public void writeBytes(byte[] b) {
        writeVarint(b.length);
        writeRaw(b, 0, b.length);
    }

    public void writeUuid(UUID u) {
        writeFixed64(u.getMostSignificantBits());
        writeFixed64(u.getLeastSignificantBits());
    }

    public void writeInstant(Instant t) {
        writeInt64(t.getEpochSecond());
        writeVarint(t.getNano());
    }

    public void writeEnum(Enum<?> e) {
        writeVarint(e.ordinal());
    }
}
