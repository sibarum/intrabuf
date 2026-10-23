package sibarum.intrabuf.nanotemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The one-line nanotemplate fillers, one per shell, for a generator to paste into the scripts it writes. Each is a
 * single line defining one function, {@code ntf}, and needs nothing the shell doesn't have built in (the POSIX
 * {@code sh} filler needs {@code cat}, which every POSIX system has). The lines are the resources next to this class;
 * this module's README says how each is called.
 *
 * <p>A filler takes a template file and the values as key and value pairs. It writes the template's body with every
 * placeholder replaced by the value of the first pair whose key is exactly the placeholder's, to standard output (to
 * the pipeline in PowerShell). It refuses a file whose header isn't a nanotemplate 1 header, a malformed body, or a
 * placeholder with no value, and writes nothing for a template it refuses.
 */
public enum NanotemplateLine {

    /** POSIX {@code sh}; Zsh fills with this one too. */
    SH_FILL("fill.sh"),
    BASH_FILL("fill.bash"),
    POWERSHELL_FILL("fill.ps1");

    private final String resource;

    NanotemplateLine(String resource) {
        this.resource = resource;
    }

    /** The resource's file name, next to this class. */
    public String resource() {
        return resource;
    }

    /** The line, without its line ending. */
    public String code() {
        try (InputStream in = NanotemplateLine.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
