package sibarum.intrabuf.tests;

import org.junit.jupiter.api.Test;
import sibarum.intrabuf.Header;
import sibarum.intrabuf.IntrabufException;
import sibarum.intrabuf.MessageCodec;
import sibarum.intrabuf.SchemaInfo;
import sibarum.intrabuf.dynamic.DynamicDecoder;
import sibarum.intrabuf.tests.CompatSchemas.PointV1;
import sibarum.intrabuf.tests.CompatSchemas.ProfileV1;
import sibarum.intrabuf.tests.CompatSchemas.ShapeWithPointV1;

import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Hostile input: a decoder may succeed or throw {@link IntrabufException}, and nothing else. No
 * {@code ArrayIndexOutOfBoundsException}, no {@code OutOfMemoryError} from a forged count, no stack overflow.
 */
class RobustnessTest {

    private record Case(String name, MessageCodec<?> codec, byte[] payload) {
    }

    private static List<Case> cases() {
        return List.of(
                new Case("everything", TypeSchemas_EverythingIntrabuf.CODEC, TypeSchemas_EverythingIntrabuf.toBytes(Samples.full())),
                new Case("wide", TypeSchemas_WideIntrabuf.CODEC, TypeSchemas_WideIntrabuf.toBytes(Samples.wide(0x1234567)) ),
                new Case("tree", TypeSchemas_TreeNodeIntrabuf.CODEC, TypeSchemas_TreeNodeIntrabuf.toBytes(Samples.tree(30))),
                new Case("shipment", TypeSchemas_ShipmentIntrabuf.CODEC, TypeSchemas_ShipmentIntrabuf.toBytes(Samples.shipment())),
                new Case("profile", CompatSchemas_ProfileV1Intrabuf.CODEC,
                        CompatSchemas_ProfileV1Intrabuf.toBytes(new ProfileV1("x", 1, "e", true, List.of("a", "b")))),
                new Case("shape", CompatSchemas_ShapeWithPointV1Intrabuf.CODEC, CompatSchemas_ShapeWithPointV1Intrabuf.toBytes(
                        new ShapeWithPointV1("s", List.of(new PointV1(1, 2, 3, true), new PointV1(4, 5, null, false))))),
                // A fingerprint in the header: a corrupt minor next to a matching fingerprint is decoded, not rejected.
                new Case("profile snapshot", CompatSchemas_ProfileV1Intrabuf.CODEC, snapshotProfile()));
    }

    /** A ProfileV1 payload re-headed as a snapshot build of the same structure wrote it: header with fingerprint. */
    private static byte[] snapshotProfile() {
        byte[] release = CompatSchemas_ProfileV1Intrabuf.toBytes(new ProfileV1("x", 1, "e", true, List.of("a", "b")));
        SchemaInfo s = CompatSchemas_ProfileV1Intrabuf.SCHEMA;
        byte[] h = Header.encode(new SchemaInfo(s.name(), s.javaType(), s.id(), s.major(), s.minor(), s.compat(),
                s.fingerprint(), true, "1.0.0-SNAPSHOT"));
        int body = Header.peek(release).size();
        byte[] out = java.util.Arrays.copyOf(h, h.length + release.length - body);
        System.arraycopy(release, body, out, h.length, release.length - body);
        return out;
    }

    @Test
    void everyHeaderMinorFailsCleanlyOrDecodes() {
        // The minor varint follows magic, flags and the 4-byte id. Every one-byte value of it, beside a fingerprint
        // that still matches, must decode or throw IntrabufException.
        DynamicDecoder dynamic = new DynamicDecoder(Manifests.all());
        byte[] p = snapshotProfile();
        int minorAt = 7;
        assertEquals(CompatSchemas_ProfileV1Intrabuf.SCHEMA.minor(), p[minorAt]);
        for (int v = 0; v < 256; v++) {
            byte[] q = p.clone();
            q[minorAt] = (byte) v;
            survives("minor byte " + v, q, CompatSchemas_ProfileV1Intrabuf.CODEC::decode);
            survives("dynamic, minor byte " + v, q, dynamic::decode);
        }
    }

    private static void survives(String what, byte[] input, Consumer<byte[]> decode) {
        try {
            decode.accept(input);
        } catch (IntrabufException expected) {
            // fine
        } catch (Throwable t) {
            AssertionError e = new AssertionError(what + ": " + t, t);
            throw e;
        }
    }

    @Test
    void everyTruncationFailsCleanly() {
        DynamicDecoder dynamic = new DynamicDecoder(Manifests.all());
        for (Case c : cases()) {
            for (int n = 0; n < c.payload().length; n++) {
                byte[] cut = java.util.Arrays.copyOf(c.payload(), n);
                assertThrows(IntrabufException.class, () -> c.codec().decode(cut), c.name() + " truncated to " + n);
                survives(c.name() + " dynamic, truncated to " + n, cut, dynamic::decode);
            }
        }
    }

    @Test
    void randomCorruptionNeverEscapesAsAnotherException() {
        Random rnd = new Random(20261005);
        DynamicDecoder dynamic = new DynamicDecoder(Manifests.all());
        for (Case c : cases()) {
            for (int iter = 0; iter < 3000; iter++) {
                byte[] p = c.payload().clone();
                int flips = 1 + rnd.nextInt(4);
                for (int k = 0; k < flips; k++) {
                    // Mostly hit the body; the header is checked byte-for-byte anyway.
                    int at = rnd.nextInt(p.length);
                    p[at] = rnd.nextInt(4) == 0 ? (byte) 0xFF : (byte) rnd.nextInt(256);
                }
                survives(c.name() + " iteration " + iter, p, c.codec()::decode);
                survives(c.name() + " dynamic iteration " + iter, p, dynamic::decode);
            }
        }
    }

    @Test
    void forgedHugeCountsDoNotAllocate() {
        byte[] p = CompatSchemas_ProfileV1Intrabuf.toBytes(new ProfileV1("x", 1, null, false, List.of()));
        // Last byte is the tags count (0). Replace it with a 5-byte varint for ~2 billion elements.
        byte[] forged = java.util.Arrays.copyOf(p, p.length + 4);
        int at = p.length - 1;
        forged[at] = (byte) 0xFF;
        forged[at + 1] = (byte) 0xFF;
        forged[at + 2] = (byte) 0xFF;
        forged[at + 3] = (byte) 0xFF;
        forged[at + 4] = (byte) 0x07;
        try {
            CompatSchemas_ProfileV1Intrabuf.fromBytes(forged);
            fail("decoded a list claiming 2^31-1 elements from 0 bytes");
        } catch (IntrabufException expected) {
            // truncated, with no giant allocation on the way
        }
    }
}
