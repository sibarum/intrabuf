package sibarum.intrabuf.nanobuf;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The one-line nanobuf readers and writers, one per shell, for a generator to paste into the scripts it writes. Each
 * is a single line defining one function, {@code nbr} to read or {@code nbw} to write, and needs nothing the shell
 * doesn't have built in (the POSIX {@code sh} reader needs {@code awk}, which every POSIX system has). The lines are
 * the resources next to this class; this module's README says how each is called.
 *
 * <p>A reader takes a file, the schema name and its major version. It refuses a file whose header names another
 * schema or major, or has no header, and otherwise hands every row after the header to the caller: as arguments to a
 * function in Bash and Zsh, to an awk action in {@code sh}, as a {@code string[]} on the pipeline in PowerShell. A
 * writer takes a row's fields, refuses one that contains {@code 0x1E} or {@code 0x1F} (PowerShell also refuses NUL,
 * which the other shells can't hold in a string), and writes nothing for a row it refuses.
 */
public enum NanobufLine {

    SH_READ("read.sh"),
    SH_WRITE("write.sh"),
    BASH_READ("read.bash"),
    BASH_WRITE("write.bash"),
    /** Zsh writes with {@link #BASH_WRITE}, which is valid Zsh as it stands. */
    ZSH_READ("read.zsh"),
    POWERSHELL_READ("read.ps1"),
    POWERSHELL_WRITE("write.ps1");

    private final String resource;

    NanobufLine(String resource) {
        this.resource = resource;
    }

    /** The resource's file name, next to this class. */
    public String resource() {
        return resource;
    }

    /** The line, without its line ending. */
    public String code() {
        try (InputStream in = NanobufLine.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
