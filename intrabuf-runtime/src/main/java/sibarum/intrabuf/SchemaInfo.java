package sibarum.intrabuf;

/**
 * What a codec knows about the schema it was generated from: everything the header carries, plus the build it came
 * from. Generated codecs hold one as {@code SCHEMA}.
 *
 * @param name         schema name (the record's canonical name unless overridden); its hash is {@code id}
 * @param javaType     canonical name of the record the codec was generated for
 * @param id           32-bit hash of {@code name}; identifies the schema on the wire
 * @param major        breaking-change version; readers reject any other major
 * @param minor        additive-change version; how it is checked depends on {@code compat}
 * @param compat       evolution mode
 * @param fingerprint  32-bit hash of the structure of this message and every type it reaches
 * @param snapshot     whether the codec was generated in a snapshot (development) build
 * @param buildVersion the version of the build that generated the codec, for humans
 */
public record SchemaInfo(String name, String javaType, int id, int major, int minor, Compat compat,
                         int fingerprint, boolean snapshot, String buildVersion) {

    /**
     * Whether payloads carry the fingerprint. STRICT needs it to catch unversioned edits; a snapshot build needs it
     * because its version numbers do not move while its schemas do.
     */
    public boolean writesFingerprint() {
        return compat == Compat.STRICT || snapshot;
    }

    public String version() {
        return major + "." + minor;
    }

    @Override
    public String toString() {
        return name + " v" + version() + " " + compat + (snapshot ? " snapshot" : "")
                + " fp=" + Hex.int32(fingerprint) + " (" + buildVersion + ")";
    }
}
