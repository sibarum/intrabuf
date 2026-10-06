package sibarum.intrabuf;

/**
 * The header every payload starts with. Release payloads in BACKWARD or FULL mode spend 8 bytes on it (4 more with a
 * fingerprint):
 *
 * <pre>
 *   byte    0xB0 | wireVersion      magic nibble 0xB, then the wire format version (1)
 *   byte    flags                   bit 0 snapshot, bit 1 fingerprint present, bits 2-3 compat, bits 4-7 zero
 *   fixed32 schema id               hash of the schema name
 *   varint  major
 *   varint  minor
 *   fixed32 fingerprint             only if flag bit 1
 * </pre>
 *
 * @param hasFingerprint whether the payload carried a fingerprint; {@code fingerprint} is 0 if not
 * @param size           how many bytes the header occupied
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

    /** The header a codec for {@code s} writes: constant per codec, so codecs precompute it. */
    public static byte[] encode(SchemaInfo s) {
        IbWriter w = new IbWriter(16);
        w.writeInt8((byte) ((MAGIC << 4) | WIRE_VERSION));
        int flags = s.compat().ordinal() << COMPAT_SHIFT;
        if (s.snapshot()) {
            flags |= FLAG_SNAPSHOT;
        }
        if (s.writesFingerprint()) {
            flags |= FLAG_FINGERPRINT;
        }
        w.writeInt8((byte) flags);
        w.writeFixed32(s.id());
        w.writeVarint(s.major());
        w.writeVarint(s.minor());
        if (s.writesFingerprint()) {
            w.writeFixed32(s.fingerprint());
        }
        return w.toByteArray();
    }

    /** Reads a header, leaving {@code r} at the start of the body. */
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

    /** Reads just the header of a payload, e.g. to route it to the right codec. */
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
