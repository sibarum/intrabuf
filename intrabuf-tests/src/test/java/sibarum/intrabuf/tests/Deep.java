package sibarum.intrabuf.tests;

import sibarum.intrabuf.dynamic.DynValue.DynEnum;
import sibarum.intrabuf.dynamic.DynValue.DynList;
import sibarum.intrabuf.dynamic.DynValue.DynMap;
import sibarum.intrabuf.dynamic.DynValue.DynMessage;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural comparisons that see through records holding {@code byte[]}, and from records to dynamic values. */
final class Deep {

    private Deep() {
    }

    static void assertDeepEquals(Object expected, Object actual) {
        assertDeepEquals(expected, actual, "$");
    }

    private static void assertDeepEquals(Object e, Object a, String path) {
        if (e == null || a == null) {
            assertEquals(e, a, path);
            return;
        }
        switch (e) {
            case byte[] eb -> assertArrayEquals(eb, (byte[]) a, path);
            case Record er -> {
                assertEquals(er.getClass(), a.getClass(), path);
                for (RecordComponent rc : er.getClass().getRecordComponents()) {
                    assertDeepEquals(get(rc, er), get(rc, a), path + "." + rc.getName());
                }
            }
            case List<?> el -> {
                List<?> al = (List<?>) a;
                assertEquals(el.size(), al.size(), path + ".size");
                for (int i = 0; i < el.size(); i++) {
                    assertDeepEquals(el.get(i), al.get(i), path + "[" + i + "]");
                }
            }
            case Map<?, ?> em -> {
                Map<?, ?> am = (Map<?, ?>) a;
                assertEquals(em.keySet(), am.keySet(), path + ".keys");
                for (Map.Entry<?, ?> x : em.entrySet()) {
                    assertDeepEquals(x.getValue(), am.get(x.getKey()), path + "[" + x.getKey() + "]");
                }
            }
            case Optional<?> eo -> {
                Optional<?> ao = (Optional<?>) a;
                assertEquals(eo.isPresent(), ao.isPresent(), path + ".isPresent");
                eo.ifPresent(v -> assertDeepEquals(v, ao.get(), path + ".get"));
            }
            default -> assertEquals(e, a, path);
        }
    }

    private static Object get(RecordComponent rc, Object r) {
        try {
            rc.getAccessor().setAccessible(true);
            return rc.getAccessor().invoke(r);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    /** Asserts that the dynamic decoder's view of a value is the generated codec's view of it. */
    static void assertMatchesDynamic(Object java, Object dyn) {
        assertMatchesDynamic(java, dyn, "$");
    }

    private static void assertMatchesDynamic(Object j, Object d, String path) {
        if (j == null) {
            assertNull(d, path);
            return;
        }
        switch (j) {
            case Record r -> {
                DynMessage m = assertInstanceOf(DynMessage.class, d, path);
                for (RecordComponent rc : r.getClass().getRecordComponents()) {
                    assertMatchesDynamic(get(rc, r), m.get(rc.getName()), path + "." + rc.getName());
                }
            }
            case Collection<?> c -> {
                DynList l = assertInstanceOf(DynList.class, d, path);
                assertEquals(c.size(), l.elements().size(), path + ".size");
                Iterator<?> it = c.iterator();
                for (int i = 0; it.hasNext(); i++) {
                    assertMatchesDynamic(it.next(), l.elements().get(i), path + "[" + i + "]");
                }
            }
            case Map<?, ?> mp -> {
                DynMap dm = assertInstanceOf(DynMap.class, d, path);
                List<Map.Entry<?, ?>> entries = new ArrayList<>(mp.entrySet());
                assertEquals(entries.size(), dm.entries().size(), path + ".size");
                for (int i = 0; i < entries.size(); i++) {
                    assertMatchesDynamic(entries.get(i).getKey(), dm.entries().get(i).getKey(), path + "{key " + i + "}");
                    assertMatchesDynamic(entries.get(i).getValue(), dm.entries().get(i).getValue(), path + "{value " + i + "}");
                }
            }
            case Optional<?> o -> {
                Optional<?> od = assertInstanceOf(Optional.class, d, path);
                assertEquals(o.isPresent(), od.isPresent(), path);
                o.ifPresent(v -> assertMatchesDynamic(v, od.get(), path + ".get"));
            }
            case Enum<?> e -> {
                DynEnum de = assertInstanceOf(DynEnum.class, d, path);
                assertEquals(e.name(), de.name(), path);
                assertEquals(e.ordinal(), de.ordinal(), path);
            }
            case byte[] b -> assertArrayEquals(b, (byte[]) d, path);
            default -> {
                assertTrue(d != null, path + " is null");
                assertEquals(j, d, path);
            }
        }
    }
}
