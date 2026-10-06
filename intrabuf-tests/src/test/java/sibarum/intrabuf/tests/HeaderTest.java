package sibarum.intrabuf.tests;

import org.junit.jupiter.api.Test;
import sibarum.intrabuf.Compat;
import sibarum.intrabuf.Header;
import sibarum.intrabuf.HeaderCodec;
import sibarum.intrabuf.HeaderRole;
import sibarum.intrabuf.IbReader;
import sibarum.intrabuf.IbWriter;
import sibarum.intrabuf.IncompatiblePayloadException;
import sibarum.intrabuf.IncompatiblePayloadException.Reason;
import sibarum.intrabuf.IntrabufException;
import sibarum.intrabuf.MessageCodec;
import sibarum.intrabuf.SchemaInfo;
import sibarum.intrabuf.dynamic.DynamicDecoder;
import sibarum.intrabuf.schema.HeaderDef;
import sibarum.intrabuf.schema.Manifest;
import sibarum.intrabuf.schema.MessageDef;
import sibarum.intrabuf.tests.HeaderSchemas.Envelope;
import sibarum.intrabuf.tests.HeaderSchemas.Ping;
import sibarum.intrabuf.tests.HeaderSchemas.Pong;
import sibarum.intrabuf.tests.HeaderSchemas.PongV0;
import sibarum.intrabuf.tests.HeaderSchemas.Tight;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.intrabuf.tests.Deep.assertMatchesDynamic;

/** Application-defined headers: the roles carry intrabuf's facts, everything else is the application's. */
class HeaderTest {

    private static final HeaderCodec<Envelope> ENVELOPE = HeaderSchemas_EnvelopeIntrabuf.CODEC;

    /** A frame as an application writes it: its envelope, then the body. */
    private static <T> byte[] frame(MessageCodec<T> codec, T value, long correlationId) {
        IbWriter w = new IbWriter();
        Envelope env = HeaderSchemas_EnvelopeIntrabuf.create(codec.schema(), (short) 0xEC51, (byte) 2, true, false, correlationId);
        ENVELOPE.write(w, env);
        codec.encodeBody(value, w);
        return w.toByteArray();
    }

    @Test
    void framesRoundTripAndRouteByTheAppsTypeId() {
        Map<Integer, MessageCodec<?>> registry = Map.of(
                HeaderSchemas_PingIntrabuf.ID, HeaderSchemas_PingIntrabuf.CODEC,
                HeaderSchemas_PongIntrabuf.ID, HeaderSchemas_PongIntrabuf.CODEC);
        assertEquals(2, HeaderSchemas_PingIntrabuf.ID);
        assertEquals(3, HeaderSchemas_PongIntrabuf.ID);

        for (Object msg : new Object[]{new Ping(41), new Pong(42, "hi")}) {
            @SuppressWarnings("unchecked")
            MessageCodec<Object> codec = (MessageCodec<Object>) (MessageCodec<?>) (msg instanceof Ping
                    ? HeaderSchemas_PingIntrabuf.CODEC : HeaderSchemas_PongIntrabuf.CODEC);
            byte[] bytes = frame(codec, msg, 77);

            IbReader r = new IbReader(bytes);
            HeaderCodec.Frame<Envelope> f = ENVELOPE.readFrame(r);
            assertEquals((short) 0xEC51, f.header().magic());
            assertEquals(77, f.header().correlationId());
            assertTrue(f.header().request());
            assertEquals(codec.schema().id(), f.facts().schemaId());
            assertEquals(msg, registry.get(f.facts().schemaId()).decodeBody(f.facts(), r));
        }
    }

    @Test
    void fingerprintIsCarriedOnlyWhenNeeded() {
        Envelope strict = ENVELOPE.read(new IbReader(frame(HeaderSchemas_PingIntrabuf.CODEC, new Ping(1), 0)));
        assertEquals(HeaderSchemas_PingIntrabuf.FINGERPRINT, strict.fingerprint());
        assertEquals(Compat.STRICT, strict.compat());

        byte[] pong = frame(HeaderSchemas_PongIntrabuf.CODEC, new Pong(1, null), 0);
        HeaderCodec.Frame<Envelope> f = ENVELOPE.readFrame(new IbReader(pong));
        assertNull(f.header().fingerprint());
        // bitset 1 (snapshot, fingerprint presence, request, reply) + magic 2 + protocolVersion 1 + wire 1 + typeId 1
        // + major 1 + minor 1 + compat 1 + correlationId 8. elektro-Q's fixed envelope is 24.
        assertEquals(17, f.facts().size());
    }

    @Test
    void compatibilityRulesApplyThroughTheAppsHeader() {
        byte[] newer = frame(HeaderSchemas_PongIntrabuf.CODEC, new Pong(5, "new"), 0);
        IncompatiblePayloadException e = assertThrows(IncompatiblePayloadException.class,
                () -> ENVELOPE.decode(HeaderSchemas_PongV0Intrabuf.CODEC, new IbReader(newer)));
        assertEquals(Reason.NEWER_MINOR, e.reason());

        byte[] older = frame(HeaderSchemas_PongV0Intrabuf.CODEC, new PongV0(5), 0);
        assertEquals(new Pong(5, null), ENVELOPE.decode(HeaderSchemas_PongIntrabuf.CODEC, new IbReader(older)));

        byte[] ping = frame(HeaderSchemas_PingIntrabuf.CODEC, new Ping(1), 0);
        assertEquals(Reason.SCHEMA_MISMATCH, assertThrows(IncompatiblePayloadException.class,
                () -> ENVELOPE.decode(HeaderSchemas_PongIntrabuf.CODEC, new IbReader(ping))).reason());
    }

    @Test
    void anAlwaysPresentFingerprintIsTheRealOne() {
        IbWriter w = new IbWriter();
        HeaderSchemas_TightIntrabuf.CODEC.write(w, HeaderSchemas_TightIntrabuf.create(HeaderSchemas_PongIntrabuf.SCHEMA));
        HeaderSchemas_PongIntrabuf.CODEC.encodeBody(new Pong(9, "x"), w);
        IbReader r = new IbReader(w.toByteArray());
        HeaderCodec.Frame<Tight> f = HeaderSchemas_TightIntrabuf.CODEC.readFrame(r);
        assertEquals(HeaderSchemas_PongIntrabuf.FINGERPRINT, f.header().fingerprint());
        assertTrue(f.facts().hasFingerprint());
        assertEquals(new Pong(9, "x"), HeaderSchemas_PongIntrabuf.CODEC.decodeBody(f.facts(), r));
    }

    @Test
    void versionsThatDoNotFitTheHeaderFieldAreRefused() {
        SchemaInfo big = new SchemaInfo("x.Big", "x.Big", 1, 300, 0, Compat.STRICT, 0, false, "1");
        IntrabufException e = assertThrows(IntrabufException.class, () -> HeaderSchemas_TightIntrabuf.create(big));
        assertTrue(e.getMessage().contains("MAJOR 300"), e.getMessage());
    }

    @Test
    void theBuiltInHeaderIsAHeaderCodecToo() {
        byte[] payload = HeaderSchemas_PongIntrabuf.toBytes(new Pong(3, "b"));
        IbReader r = new IbReader(payload);
        HeaderCodec.Frame<Header> f = Header.CODEC.readFrame(r);
        assertEquals(Header.peek(payload), f.facts());
        assertEquals(new Pong(3, "b"), HeaderSchemas_PongIntrabuf.CODEC.decodeBody(f.facts(), r));

        IbWriter w = new IbWriter();
        Header.CODEC.write(w, f.header());
        assertEquals(f.header(), Header.peek(w.toByteArray()));
    }

    @Test
    void manifestDescribesTheHeaderAndTheDynamicDecoderReadsFrames() {
        Manifest m = Manifests.load(Manifests.TESTS);
        HeaderDef env = m.headers().stream().filter(h -> h.name().equals("test.Envelope")).findFirst().orElseThrow();
        assertEquals(HeaderRole.SCHEMA_ID, env.roles().get("typeId"));
        assertEquals(7, env.roles().size());
        MessageDef ping = m.messages().stream().filter(d -> d.name().equals("test.Ping")).findFirst().orElseThrow();
        assertEquals(2, ping.info().id());

        DynamicDecoder.Decoded d = new DynamicDecoder(Manifests.all())
                .decode(frame(HeaderSchemas_PongIntrabuf.CODEC, new Pong(8, "dyn"), 1234), env);
        assertMatchesDynamic(new Pong(8, "dyn"), d.value());
        assertNotNull(d.frame());
        assertEquals(1234L, d.frame().get("correlationId"));
        assertEquals(3, d.header().schemaId());
    }

    @Test
    void corruptFramesFailCleanly() {
        byte[] good = frame(HeaderSchemas_PongIntrabuf.CODEC, new Pong(8, "fuzz"), 99);
        Random rnd = new Random(7);
        HeaderDef env = Manifests.all().header("test.Envelope");
        DynamicDecoder dynamic = new DynamicDecoder(Manifests.all());
        for (int i = 0; i < 5000; i++) {
            byte[] p = good.clone();
            p[rnd.nextInt(p.length)] = (byte) rnd.nextInt(256);
            for (Runnable decode : new Runnable[]{
                    () -> ENVELOPE.decode(HeaderSchemas_PongIntrabuf.CODEC, new IbReader(p)),
                    () -> dynamic.decode(p, env)}) {
                try {
                    decode.run();
                } catch (IntrabufException expected) {
                    // fine
                }
            }
        }
    }
}
