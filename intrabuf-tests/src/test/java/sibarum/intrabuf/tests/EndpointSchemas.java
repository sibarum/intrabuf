package sibarum.intrabuf.tests;

import sibarum.intrabuf.annotation.IntrabufEndpoint;
import sibarum.intrabuf.tests.PromotionSchemas.Account;
import sibarum.intrabuf.tests.PromotionSchemas.Ledger;
import sibarum.intrabuf.tests.CompatSchemas.PointV1;

/** An endpoint as an application declares it: accounts go up, ledgers and points come back. */
public final class EndpointSchemas {

    private EndpointSchemas() {
    }

    @IntrabufEndpoint(name = "test.Accounts", requests = Account.class, responses = {Ledger.class, PointV1.class},
            optional = PointV1.class)
    public interface Accounts {
    }
}
