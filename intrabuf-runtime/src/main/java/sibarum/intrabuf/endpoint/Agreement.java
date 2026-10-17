package sibarum.intrabuf.endpoint;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.SchemaInfo;
import sibarum.intrabuf.endpoint.Descriptor.Role;
import sibarum.intrabuf.endpoint.EndpointDef.Member;
import sibarum.intrabuf.endpoint.EndpointDef.Nested;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether two peers of an endpoint can talk, decided from their {@link Descriptor}s alone, before any payload is
 * sent. It is a pure function of the two: the verdict for "A sends S to B" is the same cell whichever of them
 * computes it, so the sender knows not to send exactly when the receiver knows to reject, and neither has to tell the
 * other. The verdicts follow the per-payload compatibility decision, which stays as the backstop.
 *
 * <pre>
 *   Agreement a = Agreement.of(OrdersIntrabuf.client(), Hello.decode(peerHello)).require();  // fails with the table
 *   a.checkSend(CancelOrderIntrabuf.SCHEMA);                                                 // before encoding
 * </pre>
 */
public final class Agreement {

    /** What happens to one schema sent one way. */
    public enum Verdict {
        /** Same version and structure. */
        IDENTICAL(true),
        /** The reader reads an older version and promotes it. */
        PROMOTES(true),
        /** The reader is older; the schema is FULL, so it reads its own fields and passes over the rest. */
        DEMOTES(true),
        /** An optional schema the reader can't take: the handshake succeeds and the writer doesn't send it. */
        NOT_SENT(true),
        /** The reader doesn't receive this schema at all. */
        NOT_RECEIVED(false),
        /** The writer is a newer major than the reader. Majors never demote. */
        NEWER_MAJOR(false),
        /** The writer is a newer minor of a BACKWARD schema, which doesn't demote. */
        NEWER_MINOR(false),
        /** The writer is older than the reader's oldest version. */
        BELOW_OLDEST(false),
        /** A STRICT schema at different minors. */
        MINOR_MISMATCH(false),
        /** A STRICT schema at the same version with different structures: an unversioned edit. */
        FINGERPRINT_MISMATCH(false),
        /** The two disagree about the schema's compatibility mode. */
        COMPAT_MISMATCH(false),
        /** The writer is a snapshot build whose structure isn't the reader's. */
        SNAPSHOT_DRIFT(false);

        private final boolean ok;

        Verdict(boolean ok) {
            this.ok = ok;
        }

        public boolean ok() {
            return ok;
        }

        /** Whether payloads of the schema go out: not for {@link #NOT_SENT}, or any failure. */
        public boolean sends() {
            return ok && this != NOT_SENT;
        }
    }

    /**
     * One schema, one way.
     *
     * @param sender   the role of the peer that writes it
     * @param sent     what the writer writes
     * @param received what the reader reads, or null if it doesn't receive the schema
     * @param why      for {@link Verdict#NOT_SENT}, the failure it stands in for; otherwise {@code verdict}
     * @param nested   the nested message the verdict is about, when it is a nested one's; otherwise null
     */
    public record Cell(Role sender, Member sent, Member received, Verdict verdict, Verdict why, Nested nested) {
    }

    /** A verdict, and the nested message it is about if it isn't the member itself. */
    record Outcome(Verdict verdict, Nested nested) {
    }

    private final Descriptor mine;
    private final Descriptor theirs;
    private final String problem;
    private final List<Cell> cells;

    private Agreement(Descriptor mine, Descriptor theirs, String problem, List<Cell> cells) {
        this.mine = mine;
        this.theirs = theirs;
        this.problem = problem;
        this.cells = List.copyOf(cells);
    }

    /** The agreement between this peer and the other, as both of them compute it. */
    public static Agreement of(Descriptor mine, Descriptor theirs) {
        String problem = null;
        if (!mine.endpoint().name().equals(theirs.endpoint().name())) {
            problem = "this peer speaks " + mine.endpoint().name() + " and the other speaks " + theirs.endpoint().name();
        } else if (mine.role() == theirs.role()) {
            problem = "both peers are " + mine.role().name().toLowerCase() + "s";
        }
        List<Cell> cells = new ArrayList<>();
        if (problem == null) {
            cells.addAll(way(mine, theirs));
            cells.addAll(way(theirs, mine));
        }
        return new Agreement(mine, theirs, problem, cells);
    }

    /** The cells for everything {@code writer} sends to {@code reader}. */
    private static List<Cell> way(Descriptor writer, Descriptor reader) {
        List<Cell> out = new ArrayList<>();
        for (Member sent : writer.sends()) {
            Member received = reader.endpoint().member(sent.id(), sent.side());
            Outcome o = outcome(sent, writer.snapshot(), received);
            Verdict v = o.verdict();
            out.add(new Cell(writer.role(), sent, received, !v.ok() && sent.optional() ? Verdict.NOT_SENT : v, v, o.nested()));
        }
        return out;
    }

    /**
     * The member's verdict, then, if it reads, each message it holds: a BACKWARD message at a version the reader
     * reads can still hold a nested message at a minor the reader's doesn't.
     */
    static Outcome outcome(Member sent, boolean writerSnapshot, Member received) {
        Verdict v = verdict(sent, writerSnapshot, received);
        if (!v.ok() || sent.fingerprint() == received.fingerprint() && sent.version().equals(received.version())) {
            return new Outcome(v, null);
        }
        boolean current = sent.version().major() == received.version().major();
        for (Nested n : sent.nested()) {
            if (!n.written()) {
                continue;
            }
            Nested r = received.reads(n.schema(), n.version().major(), current);
            if (r == null) {
                continue; // in a field the reader passes over: only a newer FULL writer has those
            }
            Verdict nv = r.compat() != n.compat() ? Verdict.COMPAT_MISMATCH
                    : r.compat() == Compat.BACKWARD && n.version().minor() > r.version().minor() ? Verdict.NEWER_MINOR
                    : r.compat() == Compat.STRICT && !n.version().equals(r.version()) ? Verdict.MINOR_MISMATCH
                    : null;
            if (nv != null) {
                return new Outcome(nv, n);
            }
        }
        return new Outcome(v, null);
    }

    /** The per-payload decision for the member itself, made from what the two builds declare. */
    static Verdict verdict(Member sent, boolean writerSnapshot, Member received) {
        if (received == null || !received.schema().equals(sent.schema())) {
            return Verdict.NOT_RECEIVED;
        }
        Version w = sent.version();
        Version newest = received.version();
        if (w.equals(newest) && sent.fingerprint() == received.fingerprint()) {
            return Verdict.IDENTICAL;
        }
        if (w.major() > newest.major()) {
            return Verdict.NEWER_MAJOR;
        }
        if (w.compareTo(received.oldest()) < 0) {
            return Verdict.BELOW_OLDEST;
        }
        if (writerSnapshot) {
            // A snapshot's version numbers say nothing; only the same structure is known to read.
            return Verdict.SNAPSHOT_DRIFT;
        }
        if (w.major() < newest.major()) {
            return Verdict.PROMOTES;
        }
        if (sent.compat() != received.compat()) {
            return Verdict.COMPAT_MISMATCH;
        }
        if (received.compat() == Compat.STRICT) {
            return w.equals(newest) ? Verdict.FINGERPRINT_MISMATCH : Verdict.MINOR_MISMATCH;
        }
        if (w.minor() <= newest.minor()) {
            // Equal minors with different fingerprints: something nested moved on, and carries its own version.
            return w.minor() < newest.minor() ? Verdict.PROMOTES : Verdict.IDENTICAL;
        }
        return received.compat() == Compat.FULL ? Verdict.DEMOTES : Verdict.NEWER_MINOR;
    }

    public Descriptor mine() {
        return mine;
    }

    public Descriptor theirs() {
        return theirs;
    }

    /** Every schema, both ways: what this peer sends first, then what it receives. */
    public List<Cell> cells() {
        return cells;
    }

    /** Whether the peers can talk: the same endpoint from opposite sides, and every cell ok. */
    public boolean ok() {
        return problem == null && cells.stream().allMatch(c -> c.verdict().ok());
    }

    /** Returns this agreement, or throws with the whole table if the peers can't talk. */
    public Agreement require() {
        if (!ok()) {
            throw new PeerIncompatibleException(table(), this);
        }
        return this;
    }

    /** Whether this peer may send payloads of {@code schema} to the other. */
    public boolean accepts(SchemaInfo schema) {
        return ok() && cells.stream().anyMatch(c -> c.sender() == mine.role() && c.sent().id() == schema.id()
                && c.verdict().sends());
    }

    /** Throws {@code NOT_AGREED} before anything is encoded, if this peer may not send {@code schema}. */
    public void checkSend(SchemaInfo schema) {
        if (accepts(schema)) {
            return;
        }
        String why = cells.stream().filter(c -> c.sender() == mine.role() && c.sent().id() == schema.id())
                .map(c -> line(c)).findFirst()
                .orElse(ok() ? schema.name() + " is not something this " + mine.role().name().toLowerCase() + " sends on "
                        + mine.endpoint().name() : "the peers don't agree:\n" + table());
        throw new PeerIncompatibleException("NOT_AGREED: " + why, this);
    }

    /** The verdict, every schema and both ways, as the handshake reports it. */
    public String table() {
        StringBuilder sb = new StringBuilder();
        String me = "this " + mine.role().name().toLowerCase() + " (" + mine.buildVersion() + (mine.snapshot() ? ", snapshot" : "") + ")";
        String peer = "the " + theirs.role().name().toLowerCase() + " (" + theirs.buildVersion() + (theirs.snapshot() ? ", snapshot" : "") + ")";
        if (problem != null) {
            return mine.endpoint().name() + ": " + problem;
        }
        sb.append(mine.endpoint().name()).append(": ").append(me).append(" and ").append(peer)
                .append(ok() ? " agree" : " can't interoperate");
        for (Cell c : cells) {
            sb.append("\n  ").append(line(c));
        }
        return sb.toString();
    }

    private String line(Cell c) {
        boolean sending = c.sender() == mine.role();
        Member s = c.sent();
        Member r = c.received();
        String reads = r == null ? "doesn't receive it" : r.oldest().equals(r.version()) ? "reads " + r.version()
                : "reads " + r.oldest() + "–" + r.version();
        String head = sending
                ? "sends    " + s.schema() + " " + s.version() + " → " + theirs.role().name().toLowerCase() + " " + reads
                : "receives " + s.schema() + " " + s.version() + " ← " + theirs.role().name().toLowerCase() + " writes "
                + s.version() + "; this " + mine.role().name().toLowerCase() + " " + reads;
        return head + ": " + explain(c, sending);
    }

    private static String explain(Cell c, boolean sending) {
        Member s = c.sent();
        Member r = c.received();
        String reader = sending ? "the peer" : "this peer";
        String writer = sending ? "this peer" : "the peer";
        if (c.verdict() == Verdict.NOT_SENT) {
            return "optional, not sent (" + explain(new Cell(c.sender(), s, r, c.why(), c.why(), c.nested()), sending) + ")";
        }
        if (c.nested() != null) {
            Nested n = c.nested();
            Nested rn = r.reads(n.schema(), n.version().major(), s.version().major() == r.version().major());
            String what = "it holds " + n.schema() + " " + n.version() + ", which " + reader + " reads as " + rn.version();
            return switch (c.why()) {
                case NEWER_MINOR -> what + "; " + n.schema() + " is BACKWARD, so a newer minor doesn't demote: " + reader
                        + " must reach " + n.schema() + " " + n.version();
                case COMPAT_MISMATCH -> what + ", written " + n.compat() + " and read " + rn.compat();
                default -> what + "; " + n.schema() + " is STRICT, so both must be the same version";
            };
        }
        return switch (c.verdict()) {
            case IDENTICAL -> "ok";
            case PROMOTES -> "ok, promoted " + s.version() + " → " + r.version() + " by " + reader;
            case DEMOTES -> "ok, " + reader + " reads the fields of " + r.version() + " and passes over the rest (FULL)";
            case NOT_SENT -> throw new IllegalStateException();
            case NOT_RECEIVED -> reader + " doesn't receive " + s.schema() + "; deploy a build of it that does, or mark "
                    + s.schema() + " optional";
            case NEWER_MAJOR -> "majors never demote, so " + reader + " must reach major " + s.version().major()
                    + " first: deploy the reader before the writer";
            case NEWER_MINOR -> s.schema() + " is BACKWARD, so a newer minor doesn't demote: " + reader + " must reach "
                    + s.schema() + " " + s.version();
            case BELOW_OLDEST -> reader + " reads from " + r.oldest() + ": " + writer + " must reach " + r.oldest()
                    + ", or " + reader + " needs promotions from " + s.version() + " (oldest = \"" + s.version() + "\")";
            case MINOR_MISMATCH -> s.schema() + " is STRICT, so both must be the same version";
            case FINGERPRINT_MISMATCH -> "same version, different structure: " + s.schema()
                    + " changed without a version bump";
            case COMPAT_MISMATCH -> "written " + s.compat() + ", read " + r.compat();
            case SNAPSHOT_DRIFT -> writer + " is a snapshot build and its " + s.schema() + " isn't the reader's"
                    + " structure; a snapshot agrees only with an identical schema";
        };
    }

    @Override
    public String toString() {
        return table();
    }
}
