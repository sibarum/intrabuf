package sibarum.intrabuf.tests;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;
import sibarum.intrabuf.annotation.Promote;

/**
 * Three majors of one schema, as this build sees them: the current {@link Account}, and the past majors it promotes
 * from, kept as they shipped. The {@code Deployed} records are what older builds wrote with; they are the same schema
 * versions, declared again here so the tests have something that writes them.
 */
public final class PromotionSchemas {

    private PromotionSchemas() {
    }

    // ---- what this build reads -------------------------------------------------------------------------------

    /** Major 1: one name. Past: read only, to be promoted. */
    @IntrabufMessage(name = "test.Account", major = 1, compat = Compat.BACKWARD)
    public record AccountV1(long id, String name) {
    }

    /** Major 2: the name split, an email added in 2.1. Past, and itself promoted from major 1. */
    @IntrabufMessage(name = "test.Account", major = 2, minor = 1, compat = Compat.BACKWARD)
    public record AccountV2(long id, String first, String last, @Field(since = 1) String email) {

        @Promote
        static AccountV2 from(AccountV1 old) {
            int space = old.name().indexOf(' ');
            return PromotionSchemas_AccountV2Intrabuf.at2_0(old.id(), old.name().substring(0, space),
                    old.name().substring(space + 1));
        }
    }

    /** Major 3, current: FULL now, and a display name added in 3.1, computed for older payloads. */
    @IntrabufMessage(name = "test.Account", major = 3, minor = 1, compat = Compat.FULL, oldest = "1.0")
    public record Account(long id, String first, String last, String email,
                          @Field(since = 1, nonNull = true) Label display) {

        @Promote
        static Account from(AccountV2 old) {
            return PromotionSchemas_AccountIntrabuf.at3_0(old.id(), old.first(), old.last(),
                    old.email() == null ? "unknown" : old.email());
        }

        @Promote(field = "display")
        static Label display(Account older) {
            return new Label(older.first() + " " + older.last());
        }
    }

    /** A message with no default, so a non-null field of it can only be added later with a promotion. */
    @IntrabufMessage(name = "test.Label", compat = Compat.FULL)
    public record Label(String text) {
    }

    /** Also major 3, reading only from 2.1: the same chain, cut short. */
    @IntrabufMessage(name = "test.Account", major = 3, minor = 1, compat = Compat.FULL, oldest = "2.1")
    public record AccountFrom21(long id, String first, String last, String email,
                                @Field(since = 1, nonNull = true) Label display) {

        @Promote
        static AccountFrom21 from(AccountV2 old) {
            return PromotionSchemas_AccountFrom21Intrabuf.at3_0(old.id(), old.first(), old.last(), old.email());
        }

        @Promote(field = "display")
        static Label display(AccountFrom21 older) {
            return new Label(older.first());
        }
    }

    /** Holds an account, so a nested account written at 3.0 gets its display promoted too. */
    @IntrabufMessage(name = "test.Ledger", compat = Compat.FULL)
    public record Ledger(String title, Account owner) {
    }

    // ---- what older builds wrote with --------------------------------------------------------------------------

    @IntrabufMessage(name = "test.Account", major = 1, compat = Compat.BACKWARD)
    public record AccountV1Deployed(long id, String name) {
    }

    @IntrabufMessage(name = "test.Account", major = 2, compat = Compat.BACKWARD)
    public record AccountV20Deployed(long id, String first, String last) {
    }

    @IntrabufMessage(name = "test.Account", major = 2, minor = 1, compat = Compat.BACKWARD)
    public record AccountV21Deployed(long id, String first, String last, @Field(since = 1) String email) {
    }

    @IntrabufMessage(name = "test.Account", major = 3, compat = Compat.FULL)
    public record AccountV30Deployed(long id, String first, String last, String email) {
    }

    @IntrabufMessage(name = "test.Ledger", compat = Compat.FULL)
    public record LedgerDeployed(String title, AccountV30Deployed owner) {
    }
}
