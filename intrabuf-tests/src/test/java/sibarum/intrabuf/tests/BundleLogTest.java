package sibarum.intrabuf.tests;

import org.junit.jupiter.api.Test;
import sibarum.intrabuf.Header;
import sibarum.intrabuf.IncompatiblePayloadException;
import sibarum.intrabuf.IncompatiblePayloadException.Reason;
import sibarum.intrabuf.IntrabufException;
import sibarum.intrabuf.tests.BundleSchemas.Flag;
import sibarum.intrabuf.tests.BundleSchemas.Frame;
import sibarum.intrabuf.tests.BundleSchemas.KeyPress;
import sibarum.intrabuf.tests.BundleSchemas.KeyPressV0;
import sibarum.intrabuf.tests.BundleSchemas.Mark;
import sibarum.intrabuf.tests.BundleSchemas.Note;
import sibarum.intrabuf.tests.BundleSchemas.Tick;
import sibarum.intrabuf.tests.BundleSchemas.TickV0;
import sibarum.intrabuf.tests.BundleSchemas.Ticks;
import sibarum.intrabuf.tests.BundleSchemas.Trace;
import sibarum.intrabuf.tests.BundleSchemas.TraceV0;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.intrabuf.tests.Deep.assertDeepEquals;

/** Bundles as text logs: written a row at a time, read a row at a time, checked by the facts line. */
class BundleLogTest {

    private static final Instant T0 = Instant.parse("2026-10-11T10:00:00Z");

    private static Frame frame(int i) {
        return new Frame(T0.plusMillis(16L * i), 400 + i, i % 3 == 0);
    }

    @Test
    void writerAppendsWholeRowsAfterAPreambleWrittenOnce() {
        List<String> appends = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        Appendable out = new Appendable() {
            @Override
            public Appendable append(CharSequence s) {
                appends.add(s.toString());
                sb.append(s);
                return this;
            }

            @Override
            public Appendable append(CharSequence s, int start, int end) {
                return append(s.subSequence(start, end));
            }

            @Override
            public Appendable append(char c) {
                return append(String.valueOf(c));
            }
        };
        BundleSchemas_TraceIntrabuf.Writer w = BundleSchemas_TraceIntrabuf.writer(out);
        w.frames(frame(0));
        w.keys(new KeyPress(T0.plusMillis(3), "A", Optional.of("a")));
        w.marks(new Mark());
        w.frames(frame(1));
        w.notes(new Note("resized, then\nredrawn", List.of("ui"), Map.of("w", 120)));

        assertEquals("""
                #intrabuf 1 test.Trace 1.1 BACKWARD id=0x38319260
                #member frames test.Frame 1.0
                frames,at,workMicros,idle
                #member keys test.KeyPress 1.1
                keys,at,key,text
                #member marks test.Mark 1.0
                marks
                #member notes test.Note 1.0
                notes,text,tags,counts
                frames,2026-10-11T10:00:00Z,400,true
                keys,2026-10-11T10:00:00.003Z,A,a
                marks
                frames,2026-10-11T10:00:00.016Z,401,false
                notes,`resized, then
                redrawn`,[ui],{w=120}
                """, sb.toString());
        // The preamble, then exactly one append per row, each ending its line.
        assertEquals(6, appends.size());
        for (String a : appends.subList(1, appends.size())) {
            assertTrue(a.endsWith("\n"), a);
        }
    }

    @Test
    void visitorSeesRowsInTheOrderTheyWereWritten() {
        StringBuilder sb = new StringBuilder();
        BundleSchemas_TraceIntrabuf.Writer w = BundleSchemas_TraceIntrabuf.writer(sb);
        w.frames(frame(0));
        w.keys(new KeyPress(T0, "B", Optional.empty()));
        w.frames(frame(1));
        w.marks(new Mark());

        List<Object> seen = new ArrayList<>();
        Header facts = BundleSchemas_TraceIntrabuf.CODEC.read(sb.toString(), new BundleSchemas_TraceIntrabuf.Visitor() {
            @Override
            public void frames(Frame row) {
                seen.add(row);
            }

            @Override
            public void keys(KeyPress row) {
                seen.add(row);
            }
            // marks: not overridden, so ignored
        });
        assertEquals(List.of(frame(0), new KeyPress(T0, "B", Optional.empty()), frame(1)), seen);
        assertEquals(1, facts.minor());
        assertEquals(BundleSchemas_TraceIntrabuf.ID, facts.schemaId());
    }

    @Test
    void wholeBundleRoundTrips() {
        Trace t = new Trace(List.of(frame(0), frame(1), frame(2)),
                List.of(new KeyPress(T0, "Enter", Optional.of("\n")), new KeyPress(T0, ",", Optional.empty())),
                List.of(new Mark(), new Mark()),
                List.of(new Note("", List.of(), Map.of()), new Note("say \"hi\"", List.of("a,b", "#c"), Map.of("&x", 1))));
        String text = BundleSchemas_TraceIntrabuf.toText(t);
        assertDeepEquals(t, BundleSchemas_TraceIntrabuf.fromText(text));
        assertDeepEquals(t, BundleSchemas_TraceIntrabuf.fromBytes(text.getBytes(StandardCharsets.UTF_8)));

        Trace empty = new Trace(List.of(), List.of(), List.of(), List.of());
        assertDeepEquals(empty, BundleSchemas_TraceIntrabuf.fromText(BundleSchemas_TraceIntrabuf.toText(empty)));
    }

    @Test
    void rowsFromManyThreadsNeverInterleave() throws InterruptedException {
        StringBuilder sb = new StringBuilder();
        BundleSchemas_TraceIntrabuf.Writer w = BundleSchemas_TraceIntrabuf.writer(sb);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int base = t * 1000;
            threads.add(Thread.ofPlatform().start(() -> {
                for (int i = 0; i < 1000; i++) {
                    w.frames(frame(base + i));
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(4000, BundleSchemas_TraceIntrabuf.fromText(sb.toString()).frames().size());
    }

    // ---- compatibility -----------------------------------------------------------------------------------------

    @Test
    void newerReaderReadsAnOlderLog() {
        StringBuilder sb = new StringBuilder();
        BundleSchemas_TraceV0Intrabuf.Writer w = BundleSchemas_TraceV0Intrabuf.writer(sb);
        w.keys(new KeyPressV0(T0, "Q"));
        w.frames(frame(5));
        Trace t = BundleSchemas_TraceIntrabuf.fromText(sb.toString());
        assertEquals(List.of(frame(5)), t.frames());
        assertEquals(List.of(new KeyPress(T0, "Q", Optional.empty())), t.keys());
        assertEquals(List.of(), t.marks());
        assertEquals(List.of(), t.notes());
    }

    @Test
    void olderBackwardReaderRejectsANewerLog() {
        String log = BundleSchemas_TraceIntrabuf.toText(new Trace(List.of(), List.of(), List.of(), List.of()));
        IncompatiblePayloadException e = assertThrows(IncompatiblePayloadException.class,
                () -> BundleSchemas_TraceV0Intrabuf.fromText(log));
        assertEquals(Reason.NEWER_MINOR, e.reason());
    }

    @Test
    void olderFullReaderSkipsNewMembersAndNewColumns() {
        StringBuilder sb = new StringBuilder();
        BundleSchemas_TicksIntrabuf.Writer w = BundleSchemas_TicksIntrabuf.writer(sb);
        w.ticks(new Tick(1, "one"));
        w.flags(new Flag("late"));
        w.ticks(new Tick(2, "two, really"));
        assertDeepEquals(new BundleSchemas.TicksV0(List.of(new TickV0(1), new TickV0(2))),
                BundleSchemas_TicksV0Intrabuf.fromText(sb.toString()));
        assertDeepEquals(new Ticks(List.of(new Tick(1, "one"), new Tick(2, "two, really")), List.of(new Flag("late"))),
                BundleSchemas_TicksIntrabuf.fromText(sb.toString()));
    }

    @Test
    void aLogOfAnotherSchemaIsRejected() {
        String log = BundleSchemas_TicksIntrabuf.toText(new Ticks(List.of(), List.of()));
        IncompatiblePayloadException e = assertThrows(IncompatiblePayloadException.class,
                () -> BundleSchemas_TraceIntrabuf.fromText(log));
        assertEquals(Reason.SCHEMA_MISMATCH, e.reason());
    }

    // ---- hand-written logs -------------------------------------------------------------------------------------

    private static final String FACTS = "#intrabuf 1 test.Trace 1.1 BACKWARD\n";

    @Test
    void handWrittenLogMayDeclareLateCommentAndUseCrlf() {
        String log = (FACTS
                + "# a comment\n"
                + "#member frames test.Frame 1.0\n"
                + "frames, at, workMicros, idle\n"
                + "\n"
                + "frames,2026-10-11T10:00:00Z,400,true\n"
                + "#member keys test.KeyPress 1.0\n"
                + "keys,at,key\n"
                + "keys , 2026-10-11T10:00:00Z , Z\n"
                + "frames,2026-10-11T10:00:00.016Z,401,false\n").replace("\n", "\r\n");
        Trace t = BundleSchemas_TraceIntrabuf.fromText(log);
        assertEquals(List.of(frame(0), frame(1)), t.frames());
        assertEquals(List.of(new KeyPress(T0, "Z", Optional.empty())), t.keys());
    }

    private static void fails(String log, String expected) {
        IntrabufException e = assertThrows(IntrabufException.class, () -> BundleSchemas_TraceIntrabuf.fromText(log));
        assertTrue(e.getMessage().contains(expected), "expected '" + expected + "' in: " + e.getMessage());
    }

    @Test
    void malformedLogsFailNamingTheProblem() {
        String frames = "#member frames test.Frame 1.0\nframes,at,workMicros,idle\n";
        fails(FACTS + "frames,2026-10-11T10:00:00Z,400,true\n", "line 2: 'frames' is not a member declared above this row");
        fails(FACTS + frames + frames, "member frames is declared twice");
        fails(FACTS + "#member frames test.Frame 1.0\nframes,at,idle,workMicros\n", "expected at,workMicros,idle");
        fails(FACTS + "#member frames test.Frame 1.0\nat,workMicros,idle\n", "member frames's columns start with its name");
        fails(FACTS + "#member frames test.Note 1.0\nframes,text,tags,counts\n", "member frames holds test.Frame, not test.Note");
        fails(FACTS + "#member clicks test.Frame 1.0\nclicks,at,workMicros,idle\n", "test.Trace has no member clicks");
        fails(FACTS + frames + "frames,2026-10-11T10:00:00Z,400\n", "has 2 cell(s); its table has 3");
        fails(FACTS + frames + "frames,2026-10-11T10:00:00Z,many,true\n", "test.Frame.workMicros: expected an int32");
        fails(FACTS + "id\n42\n", "'id' is not a member declared above this row");
        fails(FACTS + "#message test.Frame 1.0\n&,at\n", "a log holds only '#member' declarations and rows");
        fails("#intrabuf 1 test.Trace 1.0 BACKWARD\n#member marks test.Mark 1.0\nmarks\n",
                "member marks was added in minor 1, but the log is minor 0");
        IncompatiblePayloadException e = assertThrows(IncompatiblePayloadException.class,
                () -> BundleSchemas_TraceIntrabuf.fromText(FACTS + "#member frames test.Frame 2.0\nframes,at,workMicros,idle\n"));
        assertEquals(Reason.MAJOR_MISMATCH, e.reason());
    }

    @Test
    void mangledLogsFailOnlyWithIntrabufException() {
        String log = BundleSchemas_TraceIntrabuf.toText(new Trace(List.of(frame(0), frame(1)),
                List.of(new KeyPress(T0, "Enter", Optional.of("line\nbreak"))), List.of(new Mark()),
                List.of(new Note("n, \"q\"", List.of("x"), Map.of("k", 1)))));
        Random random = new Random(7);
        String alphabet = ",\n\r`\"[]{}=#&$\\ a0";
        for (int i = 0; i < 20_000; i++) {
            StringBuilder s = new StringBuilder(log);
            for (int n = 1 + random.nextInt(3); n > 0; n--) {
                int at = random.nextInt(s.length());
                switch (random.nextInt(3)) {
                    case 0 -> s.deleteCharAt(at);
                    case 1 -> s.insert(at, alphabet.charAt(random.nextInt(alphabet.length())));
                    default -> s.setCharAt(at, alphabet.charAt(random.nextInt(alphabet.length())));
                }
            }
            try {
                BundleSchemas_TraceIntrabuf.fromText(s.toString());
            } catch (IntrabufException expected) {
                // fine: malformed
            }
        }
    }

    @Test
    void binaryIsNotABundleYet() {
        IntrabufException e = assertThrows(IntrabufException.class,
                () -> BundleSchemas_TraceIntrabuf.fromBytes(new byte[]{(byte) 0xB1, 0, 1, 2, 3, 4, 1, 1}));
        assertTrue(e.getMessage().contains("binary bundles are not implemented yet"), e.getMessage());
    }
}
