package sibarum.intrabuf.endpoint;

import sibarum.intrabuf.IntrabufException;

/**
 * Two peers of an endpoint can't talk, or this peer may not send a schema to the other. The message is the
 * agreement's table: every schema, both ways, and what is missing.
 */
public final class PeerIncompatibleException extends IntrabufException {

    private final transient Agreement agreement;

    public PeerIncompatibleException(String message, Agreement agreement) {
        super(message);
        this.agreement = agreement;
    }

    public Agreement agreement() {
        return agreement;
    }
}
