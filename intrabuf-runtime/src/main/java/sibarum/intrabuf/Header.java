package sibarum.intrabuf;

/**
 * What a payload says about the schema it was written with: the facts {@link Compatibility#check} decides on.
 *
 * <p>Where those facts live on the wire is up to a {@link HeaderCodec}. An application with its own frame header
 * declares it as an {@code @IntrabufHeader} record and marks which components carry which {@link HeaderRole}; the
 * processor generates the codec. Without one, payloads use {@link #CODEC}, intrabuf's own compact header, which
 * spends 8 bytes on a release BACKWARD or FULL payload (4 more with a fingerprint):
 *
 * <pre>
 *   byte    0xB0 | wireVersion      magic nibble 0xB, then the wire format version (1)
 *   byte    flags                   bit 0 snapshot, bit 1 fingerprint present, bits 2-3 compat, bits 4-7 zero
 *   fixed32 schema id
 *   varint  major
 *   varint  minor
 *   fixed32 fingerprint             only if flag bit 1
 * </pre>
 *
 * The built-in header is written by hand rather than generated from a record: a record body starts with its
 * bitset, and the magic byte has to come first for a reader to tell an intrabuf payload from anything else.
 *
 * @param hasFingerprint whether the payload carried a fingerprint; {@code fingerprint} is 0 if not
 * @param size           how many bytes the header occupied, or -1 if unknown
 */
public record Header(int wireVersion, boolean snapshot, Compat compat, int schemaId, int major, int minor,
                     boolean hasFingerprint, int fingerprint, int size) {

    public static final int MAGIC = 0xB;
    public static final int WIRE_VERSION = 1;

    static final int FLAG_SNAPSHOT = 1;
    static final int FLAG_FINGERPRINT = 1 << 1;
    static final int COMPAT_SHIFT = 2;
    static final int COMPAT_MASK = 0b11 << COMPAT_SHIFT;
    static final int RESERVED_MASK = 0xF0;

    private static final Compat[] COMPATS = Compat.values();

    /** The built-in header format. */
    public static final HeaderCodec<Header> CODEC = new HeaderCodec<>() {
        @Override
        protected void writeBody(IbWriter w, Header h) {
            Header.write(w, h);
        }

        @Override
        protected Header readBody(IbReader r, int minor) {
            return Header.read(r);
        }

        @Override
        public Header facts(Header h, int size) {
            return h.size() == size ? h : new Header(h.wireVersion, h.snapshot, h.compat, h.schemaId, h.major, h.minor,
                    h.hasFingerprint, h.fingerprint, size);
        }
    };

    /** The facts a writer with schema {@code s} puts in every payload, whatever header format carries them. */
    public static Header of(SchemaInfo s) {
        return new Header(WIRE_VERSION, s.snapshot(), s.compat(), s.id(), s.major(), s.minor(), s.writesFingerprint(),
                s.writesFingerprint() ? s.fingerprint() : 0, -1);
    }

    /** The built-in header a codec for {@code s} writes: constant per codec, so codecs precompute it. */
    public static byte[] encode(SchemaInfo s) {
        IbWriter w = new IbWriter(16);
        write(w, of(s));
        return w.toByteArray();
    }

    static void write(IbWriter w, Header h) {
        w.writeInt8((byte) ((MAGIC << 4) | (h.wireVersion & 0x0F)));
        int flags = h.compat.ordinal() << COMPAT_SHIFT;
        if (h.snapshot) {
            flags |= FLAG_SNAPSHOT;
        }
        if (h.hasFingerprint) {
            flags |= FLAG_FINGERPRINT;
        }
        w.writeInt8((byte) flags);
        w.writeFixed32(h.schemaId);
        w.writeVarint(h.major);
        w.writeVarint(h.minor);
        if (h.hasFingerprint) {
            w.writeFixed32(h.fingerprint);
        }
    }

    /** Reads a built-in header, leaving {@code r} at the start of the body. */
    public static Header read(IbReader r) {
        int at = r.position();
        if (r.remaining() == 0) {
            throw new IncompatiblePayloadException(IncompatiblePayloadException.Reason.NOT_INTRABUF,
                    "empty payload", null, null);
        }
        int b0 = r.readUnsignedByte();
        if ((b0 >>> 4) != MAGIC) {
            throw new IncompatiblePayloadException(IncompatiblePayloadException.Reason.NOT_INTRABUF,
                    "first byte 0x" + Integer.toHexString(b0) + " is not an intrabuf magic (0xB_)", null, null);
        }
        int wire = b0 & 0x0F;
        if (wire != WIRE_VERSION) {
            throw new IncompatiblePayloadException(IncompatiblePayloadException.Reason.UNSUPPORTED_WIRE_VERSION,
                    "wire format version " + wire + "; this runtime reads version " + WIRE_VERSION, null, null);
        }
        int flags = r.readUnsignedByte();
        int compatOrdinal = (flags & COMPAT_MASK) >>> COMPAT_SHIFT;
        if ((flags & RESERVED_MASK) != 0 || compatOrdinal >= COMPATS.length) {
            throw new IncompatiblePayloadException(IncompatiblePayloadException.Reason.UNSUPPORTED_WIRE_VERSION,
                    "header flags 0x" + Integer.toHexString(flags) + " use bits this runtime does not know", null, null);
        }
        int id = r.readFixed32();
        int major = r.readCount();
        int minor = r.readCount();
        boolean hasFp = (flags & FLAG_FINGERPRINT) != 0;
        int fp = hasFp ? r.readFixed32() : 0;
        return new Header(wire, (flags & FLAG_SNAPSHOT) != 0, COMPATS[compatOrdinal], id, major, minor, hasFp, fp,
                r.position() - at);
    }

    /** Reads just the built-in header of a payload, e.g. to route it to the right codec. */
    public static Header peek(byte[] payload) {
        return read(new IbReader(payload));
    }

    public String version() {
        return major + "." + minor;
    }

    @Override
    public String toString() {
        return "id=" + Hex.int32(schemaId) + " v" + version() + " " + compat + (snapshot ? " snapshot" : "")
                + (hasFingerprint ? " fp=" + Hex.int32(fingerprint) : "") + " wire=" + wireVersion;
    }
}
