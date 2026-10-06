package sibarum.intrabuf.tests;

import sibarum.intrabuf.example.Address;
import sibarum.intrabuf.example.Color;
import sibarum.intrabuf.tests.TypeSchemas.Everything;
import sibarum.intrabuf.tests.TypeSchemas.Inner;
import sibarum.intrabuf.tests.TypeSchemas.Shipment;
import sibarum.intrabuf.tests.TypeSchemas.Suit;
import sibarum.intrabuf.tests.TypeSchemas.TreeNode;
import sibarum.intrabuf.tests.TypeSchemas.Wide;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Values used across the tests. */
final class Samples {

    private Samples() {
    }

    static Everything full() {
        Map<String, List<Long>> mapOfLists = new LinkedHashMap<>();
        mapOfLists.put("primes", List.of(2L, 3L, 5L, 7L));
        mapOfLists.put("empty", List.of());
        Map<Suit, Inner> enumKeyed = new LinkedHashMap<>();
        enumKeyed.put(Suit.HEARTS, new Inner(1, "h"));
        enumKeyed.put(Suit.SPADES, new Inner(-1, null));
        Map<Integer, Map<String, Boolean>> nested = new LinkedHashMap<>();
        nested.put(7, Map.of("yes", true));
        nested.put(-7, Map.of());
        return new Everything(
                true, (byte) -7, (short) -300, -123456, 9_876_543_210L, 3.25f, -2.5e-300, 'λ',
                -1, Long.MIN_VALUE,
                Boolean.FALSE, (byte) 127, (short) 32767, Integer.MIN_VALUE, Long.MAX_VALUE, Float.NaN, -0.0, '\uD83D',
                "héllo, wörld 😀", new byte[]{0, 1, 2, (byte) 0xFF}, new UUID(-1L, 42L),
                Instant.parse("1969-07-20T20:17:40.123456789Z"), Suit.DIAMONDS, new Inner(5, "five"),
                List.of(0, 1, -1, Integer.MAX_VALUE), List.of(1L, -1L), new LinkedHashSet<>(List.of("a", "b", "c")),
                mapOfLists, enumKeyed, nested,
                List.of(List.of("x", "y"), List.of(), List.of("z")), List.of(new Inner(1, "one"), new Inner(2, null)),
                List.of(Suit.CLUBS, Suit.SPADES), List.of(true, false, true), List.of(new byte[]{9}, new byte[0]),
                Optional.of(-5), Optional.of(false), Optional.of(new Inner(3, "three")), Optional.of(List.of("q")),
                "x".repeat(300));
    }

    static Everything sparse() {
        return new Everything(
                false, (byte) 0, (short) 0, 0, 0L, 0f, 0d, '\0',
                0, 0L,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                "");
    }

    static Wide wide(long pattern) {
        Boolean[] b = new Boolean[33];
        for (int i = 0; i < 33; i++) {
            int state = (int) ((pattern >>> (i * 2 % 64)) & 3);
            b[i] = state == 0 ? null : state == 1 ? Boolean.FALSE : Boolean.TRUE;
        }
        return new Wide(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11], b[12], b[13], b[14],
                b[15], b[16], b[17], b[18], b[19], b[20], b[21], b[22], b[23], b[24], b[25], b[26], b[27], b[28], b[29],
                b[30], b[31], b[32], pattern % 3 == 0 ? null : "tail" + pattern);
    }

    static TreeNode tree(int depth) {
        TreeNode n = new TreeNode("leaf", List.of());
        for (int i = 1; i < depth; i++) {
            List<TreeNode> children = new ArrayList<>();
            children.add(n);
            if (i % 10 == 0) {
                children.add(new TreeNode("side" + i, List.of()));
            }
            n = new TreeNode("n" + i, children);
        }
        return n;
    }

    static Shipment shipment() {
        return new Shipment("1Z999", new Address("1 Infinite Loop", "Cupertino", null, "Suite 4"), Color.BLUE);
    }

    static Set<String> set(String... s) {
        return new LinkedHashSet<>(List.of(s));
    }
}
