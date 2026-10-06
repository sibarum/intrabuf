package sibarum.intrabuf;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/**
 * Reads the primitive encodings {@link IbWriter} writes. Every read is bounds-checked against the slice it was
 * given, and every failure is an {@link IntrabufException}: a hostile or truncated payload must never surface as an
 * {@code ArrayIndexOutOfBoundsException} or an attempt to allocate a gigabyte.
 */
public final class IbReader {

    private static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /** Maximum nesting of messages within one payload. */
    public static final int MAX_DEPTH = 256;

    private final byte[] buf;
    private final int start;
    private final int limit;
    private int pos;
    private int depth;

    public IbReader(byte[] buf) {
        this(buf, 0, buf.length);
    }

    public IbReader(byte[] buf, int off, int len) {
        if (off < 0 || len < 0 || off > buf.length - len) {
            throw new IndexOutOfBoundsException("slice [" + off + ", " + off + "+" + len + ") of " + buf.length);
        }
        this.buf = buf;
        this.start = off;
        this.pos = off;
        this.limit = off + len;
    }

    /** Offset from the start of the slice. */
    public int position() {
        return pos - start;
    }

    public void position(int p) {
        if (p < 0 || p > limit - start) {
            throw new IntrabufException("position " + p + " outside payload of " + (limit - start) + " bytes");
        }
        pos = start + p;
    }

    public int remaining() {
        return limit - pos;
    }

    private void need(int n) {
        if (n > limit - pos) {
            throw truncated(n);
        }
    }

    private IntrabufException truncated(int n) {
        return new IntrabufException("payload truncated: needed " + n + " byte(s) at offset " + position()
                + ", " + remaining() + " remain");
    }

    /**
     * Called on entering a nested message. Recursive schemas let each byte of a payload open another level, so
     * without a limit a small hostile payload could overflow the stack.
     */
    public void enter() {
        if (++depth > MAX_DEPTH) {
            throw new IntrabufException("messages nested deeper than " + MAX_DEPTH);
        }
    }

    public void exit() {
        depth--;
    }

    public void skip(int n) {
        if (n < 0) {
            throw new IntrabufException("negative skip " + n);
        }
        need(n);
        pos += n;
    }

    /** Whether the unread bytes begin with {@code prefix}. The codec header fast path. */
    public boolean startsWith(byte[] prefix) {
        int n = prefix.length;
        return n <= limit - pos && Arrays.equals(buf, pos, pos + n, prefix, 0, n);
    }

    public byte readByte() {
        need(1);
        return buf[pos++];
    }

    public int readUnsignedByte() {
        return readByte() & 0xFF;
    }

    public int readVarint() {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (pos >= limit) {
                throw truncated(1);
            }
            byte b = buf[pos++];
            result |= (b & 0x7F) << shift;
            if (b >= 0) {
                if (shift == 28 && (b & 0xF0) != 0) {
                    throw new IntrabufException("varint overflows 32 bits at offset " + (position() - 1));
                }
                return result;
            }
        }
        throw new IntrabufException("varint longer than 5 bytes at offset " + position());
    }

    public long readVarLong() {
        long result = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            if (pos >= limit) {
                throw truncated(1);
            }
            byte b = buf[pos++];
            result |= (long) (b & 0x7F) << shift;
            if (b >= 0) {
                if (shift == 63 && (b & 0x7E) != 0) {
                    throw new IntrabufException("varint overflows 64 bits at offset " + (position() - 1));
                }
                return result;
            }
        }
        throw new IntrabufException("varint longer than 10 bytes at offset " + position());
    }

    /** A length or element count: a varint that must fit in a non-negative int. */
    public int readCount() {
        int n = readVarint();
        if (n < 0) {
            throw new IntrabufException("count " + Integer.toUnsignedString(n) + " at offset " + position() + " is too large");
        }
        return n;
    }

    /**
     * An initial capacity for a collection of {@code count} elements that a hostile count cannot turn into a huge
     * allocation: no collection can have more elements than there are bytes left, give or take empty messages.
     */
    public int capacityHint(int count) {
        return Math.min(count, remaining());
    }

    /**
     * Reserves {@code nbytes} of bitset and returns where it starts, for {@link #bitsWord}. Advances past them.
     */
    public int beginBits(int nbytes) {
        need(nbytes);
        int at = pos;
        pos += nbytes;
        return at;
    }

    /**
     * Word {@code word} (bits {@code 64*word ..}) of a bitset of {@code nbytes} starting at {@code at}. Bytes past
     * the end of the bitset read as zero, which is what makes an older, shorter bitset decode its missing fields as
     * absent.
     */
    public long bitsWord(int at, int nbytes, int word) {
        int from = word << 3;
        int to = Math.min(nbytes, from + 8);
        long v = 0;
        for (int i = from; i < to; i++) {
            v |= (buf[at + i] & 0xFFL) << ((i - from) << 3);
        }
        return v;
    }

    // ---- scalar kinds ------------------------------------------------------------------------------------------

    public boolean readBool() {
        byte b = readByte();
        if ((b & ~1) != 0) {
            throw new IntrabufException("invalid boolean byte " + b + " at offset " + (position() - 1));
        }
        return b != 0;
    }

    public byte readInt8() {
        return readByte();
    }

    public short readInt16() {
        int v = readInt32();
        if (v != (short) v) {
            throw new IntrabufException("int16 out of range: " + v);
        }
        return (short) v;
    }

    public int readInt32() {
        int z = readVarint();
        return (z >>> 1) ^ -(z & 1);
    }

    public long readInt64() {
        long z = readVarLong();
        return (z >>> 1) ^ -(z & 1);
    }

    public int readFixed32() {
        need(4);
        int v = (int) INT_LE.get(buf, pos);
        pos += 4;
        return v;
    }

    public long readFixed64() {
        need(8);
        long v = (long) LONG_LE.get(buf, pos);
        pos += 8;
        return v;
    }

    public float readFloat32() {
        return Float.intBitsToFloat(readFixed32());
    }

    public double readFloat64() {
        return Double.longBitsToDouble(readFixed64());
    }

    public char readChar() {
        int v = readVarint();
        if ((v & ~0xFFFF) != 0) {
            throw new IntrabufException("char out of range: " + v);
        }
        return (char) v;
    }

    public String readString() {
        int len = readCount();
        need(len);
        String s = new String(buf, pos, len, StandardCharsets.UTF_8);
        pos += len;
        return s;
    }

    public byte[] readBytes() {
        int len = readCount();
        need(len);
        byte[] b = Arrays.copyOfRange(buf, pos, pos + len);
        pos += len;
        return b;
    }

    public UUID readUuid() {
        long msb = readFixed64();
        long lsb = readFixed64();
        return new UUID(msb, lsb);
    }

    public Instant readInstant() {
        long seconds = readInt64();
        int nanos = readVarint();
        // ofEpochSecond would quietly carry excess nanos into seconds; a writer never produces them.
        if (nanos < 0 || nanos > 999_999_999) {
            throw new IntrabufException("invalid instant nanos " + Integer.toUnsignedString(nanos));
        }
        try {
            return Instant.ofEpochSecond(seconds, nanos);
        } catch (DateTimeException | ArithmeticException e) {
            throw new IntrabufException("invalid instant " + seconds + "s " + Integer.toUnsignedString(nanos) + "ns", e);
        }
    }

    /** An enum ordinal, checked against the number of constants the reader knows. */
    public int readEnum(int constants, String type) {
        int ord = readVarint();
        if (ord < 0 || ord >= constants) {
            throw new IntrabufException("ordinal " + Integer.toUnsignedString(ord) + " is not a constant of " + type
                    + " (" + constants + " known); a newer writer may have added constants");
        }
        return ord;
    }
}
