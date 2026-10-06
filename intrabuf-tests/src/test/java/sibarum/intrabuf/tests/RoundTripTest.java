package sibarum.intrabuf.tests;

import org.junit.jupiter.api.Test;
import sibarum.intrabuf.Header;
import sibarum.intrabuf.IbReader;
import sibarum.intrabuf.IbWriter;
import sibarum.intrabuf.IntrabufException;
import sibarum.intrabuf.example.AddressIntrabuf;
import sibarum.intrabuf.tests.CompatSchemas.ProfileV0;
import sibarum.intrabuf.tests.CompatSchemas.TokenV0;
import sibarum.intrabuf.tests.TypeSchemas.Empty;
import sibarum.intrabuf.tests.TypeSchemas.Everything;
import sibarum.intrabuf.tests.TypeSchemas.Inner;
import sibarum.intrabuf.tests.TypeSchemas.Shipment;
import sibarum.intrabuf.tests.TypeSchemas.TreeNode;
import sibarum.intrabuf.tests.TypeSchemas.Wide;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.intrabuf.tests.Deep.assertDeepEquals;

class RoundTripTest {

    private static final TypeSchemas_EverythingIntrabuf EVERYTHING = TypeSchemas_EverythingIntrabuf.CODEC;

    @Test
    void everyTypeWithValues() {
        Everything v = Samples.full();
        assertDeepEquals(v, EVERYTHING.decode(EVERYTHING.encode(v)));
    }

    @Test
    void everyTypeAbsent() {
        Everything v = Samples.sparse();
        byte[] payload = EVERYTHING.encode(v);
        assertDeepEquals(v, EVERYTHING.decode(payload));
    }

    @Test
    void extremes() {
        Everything base = Samples.sparse();
        for (long l : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, -1, 0, 1, 1L << 35, -(1L << 35)}) {
            for (int i : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 0, 1, 127, 128, -64, -65}) {
                Everything v = new Everything(true, Byte.MIN_VALUE, Short.MIN_VALUE, i, l, Float.MIN_VALUE, Double.MAX_VALUE,
                        Character.MAX_VALUE, i, l, base.bBool(), base.bByte(), base.bShort(), i, l, -0.0f, Double.NaN, 'a',
                        "", new byte[0], null, null, null, null, List.of(i), List.of(l), null, null, null, null, null, null,
                        null, null, null, Optional.of(i), Optional.of(true), Optional.empty(), Optional.of(List.of()), "r");
                assertDeepEquals(v, EVERYTHING.decode(EVERYTHING.encode(v)));
            }
        }
    }

    @Test
    void stringsOfEveryLengthClass() {
        // ASCII fast path, the 127/128 boundary of the one-byte length, and multi-byte UTF-8.
        for (int n : new int[]{0, 1, 127, 128, 129, 16383, 16384, 70000}) {
            for (String unit : new String[]{"a", "é", "😀"}) {
                String s = unit.repeat(n);
                Inner v = new Inner(n, s);
                assertEquals(v, TypeSchemas_InnerIntrabuf.CODEC.decode(TypeSchemas_InnerIntrabuf.CODEC.encode(v)));
            }
        }
    }

    @Test
    void multiWordBitset() {
        for (long pattern : new long[]{0, -1, 0x5555555555555555L, 0xAAAAAAAAAAAAAAAAL, 0x123456789ABCDEFL, 1L << 63, 3}) {
            Wide w = Samples.wide(pattern);
            assertEquals(w, TypeSchemas_WideIntrabuf.CODEC.decode(TypeSchemas_WideIntrabuf.CODEC.encode(w)));
        }
    }

    @Test
    void emptyMessageIsJustAHeader() {
        byte[] payload = TypeSchemas_EmptyIntrabuf.CODEC.encode(new Empty());
        assertEquals(Header.peek(payload).size(), payload.length);
        assertEquals(new Empty(), TypeSchemas_EmptyIntrabuf.CODEC.decode(payload));
    }

    @Test
    void recursiveMessages() {
        TreeNode t = Samples.tree(200);
        assertEquals(t, TypeSchemas_TreeNodeIntrabuf.CODEC.decode(TypeSchemas_TreeNodeIntrabuf.CODEC.encode(t)));
    }

    @Test
    void nestingDepthIsLimited() {
        byte[] payload = TypeSchemas_TreeNodeIntrabuf.CODEC.encode(Samples.tree(IbReader.MAX_DEPTH + 10));
        IntrabufException e = assertThrows(IntrabufException.class, () -> TypeSchemas_TreeNodeIntrabuf.CODEC.decode(payload));
        assertTrue(e.getMessage().contains("nested deeper"), e.getMessage());
    }

    @Test
    void nestsARecordFromAnotherModule() {
        Shipment s = Samples.shipment();
        assertEquals(s, TypeSchemas_ShipmentIntrabuf.CODEC.decode(TypeSchemas_ShipmentIntrabuf.CODEC.encode(s)));
        // The nested Address uses the codec generated in intrabuf-example, not a second copy.
        assertEquals(AddressIntrabuf.FINGERPRINT, AddressIntrabuf.CODEC.schema().fingerprint());
    }

    @Test
    void nonNullFieldsRejectNull() {
        Everything v = Samples.sparse();
        Everything bad = new Everything(v.pBool(), v.pByte(), v.pShort(), v.pInt(), v.pLong(), v.pFloat(), v.pDouble(),
                v.pChar(), v.fixedInt(), v.fixedLong(), null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), null);
        IntrabufException e = assertThrows(IntrabufException.class, () -> EVERYTHING.encode(bad));
        assertTrue(e.getMessage().contains("Everything.required"), e.getMessage());
    }

    @Test
    void collectionsRejectNullElements() {
        List<Integer> ints = new ArrayList<>(Arrays.asList(1, null));
        Everything v = Samples.sparse();
        Everything bad = new Everything(v.pBool(), v.pByte(), v.pShort(), v.pInt(), v.pLong(), v.pFloat(), v.pDouble(),
                v.pChar(), v.fixedInt(), v.fixedLong(), null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, ints, null, null, null, null, null, null, null, null, null, null,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), "");
        assertThrows(IntrabufException.class, () -> EVERYTHING.encode(bad));
    }

    @Test
    void nullOptionalEncodesAsEmpty() {
        Everything v = Samples.sparse();
        Everything withNull = new Everything(v.pBool(), v.pByte(), v.pShort(), v.pInt(), v.pLong(), v.pFloat(), v.pDouble(),
                v.pChar(), v.fixedInt(), v.fixedLong(), null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, "");
        assertDeepEquals(v, EVERYTHING.decode(EVERYTHING.encode(withNull)));
    }

    @Test
    void headerCosts() {
        // A release BACKWARD/FULL payload: magic, flags, 4-byte id, major, minor.
        assertEquals(8, Header.peek(CompatSchemas_ProfileV0Intrabuf.toBytes(new ProfileV0("a", 1))).size());
        // STRICT adds the 4-byte fingerprint.
        assertEquals(12, Header.peek(CompatSchemas_TokenV0Intrabuf.toBytes(new TokenV0("a"))).size());
    }

    @Test
    void compactEncoding() {
        // Header (8) + bitset (1: name's presence bit) + "ab" (3) + zigzag(30) (1).
        assertEquals(13, CompatSchemas_ProfileV0Intrabuf.toBytes(new ProfileV0("ab", 30)).length);
        // 33 nullable Booleans and a null String live entirely in the bitset: 67 bits. FULL mode prefixes the
        // bitset with its length. Header (8) + length (1) + 9 bitset bytes.
        assertEquals(18, TypeSchemas_WideIntrabuf.toBytes(Samples.wide(0)).length);
    }

    @Test
    void reusableWriterAndSlicedReader() {
        IbWriter w = new IbWriter(4);
        Inner a = new Inner(1, "first");
        Inner b = new Inner(2, "second");
        TypeSchemas_InnerIntrabuf.CODEC.encode(a, w);
        int split = w.position();
        TypeSchemas_InnerIntrabuf.CODEC.encode(b, w);
        byte[] both = w.toByteArray();
        assertEquals(a, TypeSchemas_InnerIntrabuf.CODEC.decode(both, 0, split));
        assertEquals(b, TypeSchemas_InnerIntrabuf.CODEC.decode(both, split, both.length - split));
        // Without slicing, the second message is trailing garbage to the first decode.
        assertThrows(IntrabufException.class, () -> TypeSchemas_InnerIntrabuf.CODEC.decode(both));
        w.reset();
        TypeSchemas_InnerIntrabuf.CODEC.encode(b, w);
        assertArrayEquals(TypeSchemas_InnerIntrabuf.toBytes(b), w.toByteArray());
    }

    @Test
    void encodingIsDeterministic() {
        Everything v = Samples.full();
        assertArrayEquals(EVERYTHING.encode(v), EVERYTHING.encode(v));
        assertFalse(EVERYTHING.schema().snapshot(), "tests compile as release 1.0.0");
    }
}
