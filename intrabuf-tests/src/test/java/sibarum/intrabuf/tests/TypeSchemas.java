package sibarum.intrabuf.tests;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;
import sibarum.intrabuf.example.Address;
import sibarum.intrabuf.example.Color;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Schemas exercising every supported type. Nested in a holder class, so codecs are named {@code TypeSchemas_XIntrabuf}. */
public final class TypeSchemas {

    private TypeSchemas() {
    }

    public enum Suit { CLUBS, DIAMONDS, HEARTS, SPADES }

    @IntrabufMessage(name = "test.Inner")
    public record Inner(int a, String b) {
    }

    @IntrabufMessage(name = "test.Everything")
    public record Everything(
            boolean pBool, byte pByte, short pShort, int pInt, long pLong, float pFloat, double pDouble, char pChar,
            @Field(fixed = true) int fixedInt, @Field(fixed = true) long fixedLong,
            Boolean bBool, Byte bByte, Short bShort, Integer bInt, Long bLong, Float bFloat, Double bDouble, Character bChar,
            String string, byte[] bytes, UUID uuid, Instant instant, Suit suit, Inner inner,
            List<Integer> ints, @Field(fixed = true) List<Long> fixedLongs, Set<String> stringSet,
            Map<String, List<Long>> mapOfLists, Map<Suit, Inner> enumKeyed, Map<Integer, Map<String, Boolean>> nestedMaps,
            List<List<String>> listOfLists, List<Inner> inners, List<Suit> suits, List<Boolean> bools, List<byte[]> blobs,
            Optional<Integer> optInt, Optional<Boolean> optBool, Optional<Inner> optInner, Optional<List<String>> optList,
            @Field(nonNull = true) String required) {
    }

    /** 33 nullable Booleans: 66 bits, so the bitset spans two words. */
    @IntrabufMessage(name = "test.Wide", compat = Compat.FULL)
    public record Wide(
            Boolean b00, Boolean b01, Boolean b02, Boolean b03, Boolean b04, Boolean b05, Boolean b06, Boolean b07,
            Boolean b08, Boolean b09, Boolean b10, Boolean b11, Boolean b12, Boolean b13, Boolean b14, Boolean b15,
            Boolean b16, Boolean b17, Boolean b18, Boolean b19, Boolean b20, Boolean b21, Boolean b22, Boolean b23,
            Boolean b24, Boolean b25, Boolean b26, Boolean b27, Boolean b28, Boolean b29, Boolean b30, Boolean b31,
            Boolean b32, String tail) {
    }

    @IntrabufMessage(name = "test.Empty")
    public record Empty() {
    }

    @IntrabufMessage(name = "test.TreeNode")
    public record TreeNode(String label, List<TreeNode> children) {
    }

    /** A STRICT message nesting a FULL record compiled in another module (intrabuf-example). */
    @IntrabufMessage(name = "test.Shipment")
    public record Shipment(String tracking, Address to, Color color) {
    }
}
