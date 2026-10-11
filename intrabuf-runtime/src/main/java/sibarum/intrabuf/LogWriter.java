package sibarum.intrabuf;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Writes a bundle as a text log, one row at a time, as the rows happen. Generated bundle codecs extend it as their
 * {@code Writer}, with one method per member.
 *
 * <p>Creating a writer writes the facts line and every member's declaration. Each row then goes to the output in a
 * single {@code append}, newline included, so a reader of the output never sees part of a row from a live writer.
 * Rows from different threads don't interleave: writing is synchronized. Buffering is the output's business; wrap it
 * in a buffer and call {@link #flush()} when rows should reach it.
 */
public class LogWriter implements Closeable, Flushable {

    private final BundleCodec<?, ?> codec;
    private final Appendable out;
    private final TextWriter text = TextWriter.forLog();

    /** Starts a log on {@code out}: writes the facts line and the members' declarations. */
    protected LogWriter(BundleCodec<?, ?> codec, Appendable out) {
        this.codec = codec;
        this.out = out;
        StringBuilder sb = new StringBuilder(128);
        TextWriter.appendFacts(sb, codec.schema().name(), Header.of(codec.schema()));
        for (BundleCodec.Member m : codec.members()) {
            TextWriter.appendMember(sb, m.name(), m.codec().section());
        }
        append(sb);
    }

    /** Appends one row of member {@code member}. */
    protected final synchronized void write(int member, Object row) {
        List<BundleCodec.Member> members = codec.members();
        BundleCodec.Member m = members.get(member);
        if (row == null) {
            throw IntrabufException.nullElement(codec.schema().javaType() + "." + m.name());
        }
        append(text.logRow(m.name(), () -> writeCells(m.codec(), row)));
    }

    @SuppressWarnings("unchecked")
    private <T> void writeCells(MessageCodec<T> codec, Object row) {
        codec.writeText(text, (T) row);
    }

    private void append(CharSequence s) {
        try {
            out.append(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Flushes the output, if it can be flushed. */
    @Override
    public synchronized void flush() {
        if (out instanceof Flushable f) {
            try {
                f.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Closes the output, if it can be closed. */
    @Override
    public synchronized void close() {
        if (out instanceof Closeable c) {
            try {
                c.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
