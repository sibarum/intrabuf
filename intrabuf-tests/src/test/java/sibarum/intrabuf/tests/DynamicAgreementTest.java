package sibarum.intrabuf.tests;

import org.junit.jupiter.api.Test;
import sibarum.intrabuf.Hex;
import sibarum.intrabuf.dynamic.DynValue.DynMessage;
import sibarum.intrabuf.dynamic.DynamicDecoder;
import sibarum.intrabuf.dynamic.SchemaSet;
import sibarum.intrabuf.example.AddressIntrabuf;
import sibarum.intrabuf.schema.Fingerprint;
import sibarum.intrabuf.schema.Manifest;
import sibarum.intrabuf.schema.ManifestJson;
import sibarum.intrabuf.schema.MessageDef;
import sibarum.intrabuf.tests.CompatSchemas.PointV0;
import sibarum.intrabuf.tests.CompatSchemas.PointV1;
import sibarum.intrabuf.tests.CompatSchemas.ProfileV0;
import sibarum.intrabuf.tests.CompatSchemas.ProfileV1;
import sibarum.intrabuf.tests.CompatSchemas.TokenV0;
import sibarum.intrabuf.tests.CompatSchemas.TokenV0Edited;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.intrabuf.tests.Deep.assertMatchesDynamic;

/**
 * The generated codecs and the manifest-driven decoder implement one wire format twice. These tests hold them to
 * agreeing, and check the manifest itself says what the codecs do.
 */
class DynamicAgreementTest {

    private final DynamicDecoder dynamic = new DynamicDecoder(Manifests.all());

    private void agrees(Object value, byte[] payload) {
        DynamicDecoder.Decoded d = dynamic.decode(payload);
        assertEquals(List.of(), d.warnings());
        assertEquals(0, d.trailingBytes());
        assertMatchesDynamic(value, d.value());
    }

    @Test
    void everyType() {
        agrees(Samples.full(), TypeSchemas_EverythingIntrabuf.toBytes(Samples.full()));
        agrees(Samples.sparse(), TypeSchemas_EverythingIntrabuf.toBytes(Samples.sparse()));
    }

    @Test
    void multiWordBitsetsRecursionAndCrossModuleNesting() {
        for (long p : new long[]{0, -1, 0x5555555555555555L, 0x123456789ABCDEFL}) {
            agrees(Samples.wide(p), TypeSchemas_WideIntrabuf.toBytes(Samples.wide(p)));
        }
        agrees(Samples.tree(40), TypeSchemas_TreeNodeIntrabuf.toBytes(Samples.tree(40)));
        agrees(Samples.shipment(), TypeSchemas_ShipmentIntrabuf.toBytes(Samples.shipment()));
    }

    @Test
    void picksTheVersionThePayloadWasWrittenAt() {
        // Two versions of test.Profile are loaded; a v1.0 payload decodes with only v1.0's fields.
        DynMessage old = dynamic.decode(CompatSchemas_ProfileV0Intrabuf.toBytes(new ProfileV0("a", 1))).value();
        assertEquals(List.of("name", "age"), old.fields().stream().map(f -> f.name()).toList());
        assertEquals(0, old.minor());
        agrees(new ProfileV1("a", 1, "e", true, List.of("t")),
                CompatSchemas_ProfileV1Intrabuf.toBytes(new ProfileV1("a", 1, "e", true, List.of("t"))));

        // STRICT versions with equal numbers are told apart by fingerprint.
        agrees(new TokenV0("x"), CompatSchemas_TokenV0Intrabuf.toBytes(new TokenV0("x")));
        agrees(new TokenV0Edited("x", 5), CompatSchemas_TokenV0EditedIntrabuf.toBytes(new TokenV0Edited("x", 5)));
    }

    @Test
    void newerFullPayloadAgainstOlderManifestReportsUnknownBytes() {
        // A manifest that only knows test.Point v1.0, as an old release's would.
        Manifest tests = Manifests.load(Manifests.TESTS);
        Manifest old = new Manifest(tests.format(), tests.wireVersion(), "old", "0.9.0", false,
                tests.messages().stream().filter(m -> !m.info().javaType().endsWith("PointV1")).toList(), tests.enums());
        DynamicDecoder.Decoded d = new DynamicDecoder(new SchemaSet().add(old))
                .decode(CompatSchemas_PointV1Intrabuf.toBytes(new PointV1(4, 5, 6, true)));
        assertMatchesDynamic(new PointV0(4, 5), d.value());
        assertTrue(d.trailingBytes() > 0);
        assertFalse(d.warnings().isEmpty());
    }

    @Test
    void manifestDescribesTheCodecs() {
        Manifest m = Manifests.load(Manifests.TESTS);
        assertEquals("intrabuf-tests", m.module());
        assertEquals("1.0.0", m.buildVersion());
        assertFalse(m.snapshot());
        MessageDef everything = m.messages().stream().filter(d -> d.name().equals("test.Everything")).findFirst().orElseThrow();
        assertEquals(TypeSchemas_EverythingIntrabuf.FINGERPRINT, everything.info().fingerprint());
        assertEquals(TypeSchemas_EverythingIntrabuf.ID, everything.info().id());
        // The fingerprint can be recomputed from the manifest alone.
        SchemaSet all = Manifests.all();
        assertEquals(everything.info().fingerprint(), Fingerprint.of("test.Everything", all::latest, all::enumDef));

        // example.Address was compiled in another module, as a snapshot; the manifest says so.
        MessageDef address = m.messages().stream().filter(d -> d.name().equals("example.Address")).findFirst().orElseThrow();
        assertEquals(AddressIntrabuf.FINGERPRINT, address.info().fingerprint(), Hex.int32(address.info().fingerprint()));
        assertTrue(address.info().snapshot());
        assertEquals(AddressIntrabuf.BUILD_VERSION, address.info().buildVersion());
    }

    @Test
    void manifestJsonRoundTrips() {
        Manifest m = Manifests.load(Manifests.TESTS);
        assertEquals(m, ManifestJson.parse(ManifestJson.write(m)));
    }
}
