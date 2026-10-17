package sibarum.intrabuf.tests;

import org.junit.jupiter.api.Test;
import sibarum.intrabuf.IncompatiblePayloadException;
import sibarum.intrabuf.IncompatiblePayloadException.Reason;
import sibarum.intrabuf.IntrabufException;
import sibarum.intrabuf.tests.PromotionSchemas.Account;
import sibarum.intrabuf.tests.PromotionSchemas.AccountFrom21;
import sibarum.intrabuf.tests.PromotionSchemas.AccountV1Deployed;
import sibarum.intrabuf.tests.PromotionSchemas.AccountV20Deployed;
import sibarum.intrabuf.tests.PromotionSchemas.AccountV21Deployed;
import sibarum.intrabuf.tests.PromotionSchemas.AccountV2;
import sibarum.intrabuf.tests.PromotionSchemas.AccountV30Deployed;
import sibarum.intrabuf.tests.PromotionSchemas.Label;
import sibarum.intrabuf.tests.PromotionSchemas.Ledger;
import sibarum.intrabuf.tests.PromotionSchemas.LedgerDeployed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromotionTest {

    private static final Account ADA = new Account(7, "Ada", "Lovelace", "unknown", new Label("Ada Lovelace"));

    // ---- the chain ---------------------------------------------------------------------------------------------

    @Test
    void majorOnePromotesThroughEveryMajorAndThenTheFields() {
        byte[] v1 = PromotionSchemas_AccountV1DeployedIntrabuf.toBytes(new AccountV1Deployed(7, "Ada Lovelace"));
        assertEquals(ADA, PromotionSchemas_AccountIntrabuf.fromBytes(v1));
    }

    @Test
    void textPayloadsPromoteTheSameWay() {
        String v1 = PromotionSchemas_AccountV1DeployedIntrabuf.toText(new AccountV1Deployed(7, "Ada Lovelace"));
        assertEquals(ADA, PromotionSchemas_AccountIntrabuf.fromText(v1));
        assertEquals(ADA, PromotionSchemas_AccountIntrabuf.fromBytes(v1.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void anOlderMinorOfAPastMajorTakesItsDefaultsFirst() {
        byte[] v20 = PromotionSchemas_AccountV20DeployedIntrabuf.toBytes(new AccountV20Deployed(7, "Ada", "Lovelace"));
        assertEquals(ADA, PromotionSchemas_AccountIntrabuf.fromBytes(v20));
        byte[] v21 = PromotionSchemas_AccountV21DeployedIntrabuf.toBytes(
                new AccountV21Deployed(7, "Ada", "Lovelace", "ada@example.com"));
        assertEquals(new Account(7, "Ada", "Lovelace", "ada@example.com", new Label("Ada Lovelace")),
                PromotionSchemas_AccountIntrabuf.fromBytes(v21));
    }

    @Test
    void anOlderMinorOfTheCurrentMajorHasItsFieldsPromoted() {
        byte[] v30 = PromotionSchemas_AccountV30DeployedIntrabuf.toBytes(
                new AccountV30Deployed(7, "Ada", "Lovelace", "unknown"));
        assertEquals(ADA, PromotionSchemas_AccountIntrabuf.fromBytes(v30));
    }

    @Test
    void theCurrentVersionIsNotPromoted() {
        Account a = new Account(7, "Ada", "Lovelace", "unknown", new Label("Countess"));
        assertEquals(a, PromotionSchemas_AccountIntrabuf.fromBytes(PromotionSchemas_AccountIntrabuf.toBytes(a)));
        assertEquals(a, PromotionSchemas_AccountIntrabuf.fromText(PromotionSchemas_AccountIntrabuf.toText(a)));
    }

    @Test
    void nestedMessagesHaveTheirFieldsPromoted() {
        byte[] old = PromotionSchemas_LedgerDeployedIntrabuf.toBytes(
                new LedgerDeployed("main", new AccountV30Deployed(7, "Ada", "Lovelace", "unknown")));
        assertEquals(new Ledger("main", ADA), PromotionSchemas_LedgerIntrabuf.fromBytes(old));
        String text = PromotionSchemas_LedgerDeployedIntrabuf.toText(
                new LedgerDeployed("main", new AccountV30Deployed(7, "Ada", "Lovelace", "unknown")));
        assertEquals(new Ledger("main", ADA), PromotionSchemas_LedgerIntrabuf.fromText(text));
    }

    // ---- what is refused -------------------------------------------------------------------------------------

    @Test
    void belowTheOldestIsRefusedBeforeDecoding() {
        byte[] v1 = PromotionSchemas_AccountV1DeployedIntrabuf.toBytes(new AccountV1Deployed(7, "Ada Lovelace"));
        IncompatiblePayloadException e = assertThrows(IncompatiblePayloadException.class,
                () -> PromotionSchemas_AccountFrom21Intrabuf.fromBytes(v1));
        assertEquals(Reason.BELOW_OLDEST, e.reason());
        assertTrue(e.getMessage().contains("reads v2.1 to v3.1"), e.getMessage());
        assertTrue(e.getMessage().contains("needs a promotion"), e.getMessage());

        byte[] v20 = PromotionSchemas_AccountV20DeployedIntrabuf.toBytes(new AccountV20Deployed(7, "Ada", "Lovelace"));
        e = assertThrows(IncompatiblePayloadException.class, () -> PromotionSchemas_AccountFrom21Intrabuf.fromBytes(v20));
        assertEquals(Reason.BELOW_OLDEST, e.reason());
        assertTrue(e.getMessage().contains("(its oldest)"), e.getMessage());

        byte[] v21 = PromotionSchemas_AccountV21DeployedIntrabuf.toBytes(new AccountV21Deployed(7, "Ada", "Lovelace", "a@b"));
        assertEquals(new AccountFrom21(7, "Ada", "Lovelace", "a@b", new Label("Ada")),
                PromotionSchemas_AccountFrom21Intrabuf.fromBytes(v21));
    }

    @Test
    void aNewerMajorIsNeverRead() {
        byte[] v31 = PromotionSchemas_AccountIntrabuf.toBytes(ADA);
        assertEquals(Reason.NEWER_MAJOR, assertThrows(IncompatiblePayloadException.class,
                () -> PromotionSchemas_AccountV21DeployedIntrabuf.fromBytes(v31)).reason());
    }

    @Test
    void aPastVersionOnlyReads() {
        AccountV2 past = new AccountV2(7, "Ada", "Lovelace", null);
        IntrabufException e = assertThrows(IntrabufException.class, () -> PromotionSchemas_AccountV2Intrabuf.CODEC.encode(past));
        assertTrue(e.getMessage().contains("test.Account v2.1 is a past version"), e.getMessage());
        assertThrows(IntrabufException.class, () -> PromotionSchemas_AccountV2Intrabuf.CODEC.encodeText(past));
        // Used directly it reads from its own oldest, 2.0: the chain below it runs for the codec that declares oldest.
        byte[] v1 = PromotionSchemas_AccountV1DeployedIntrabuf.toBytes(new AccountV1Deployed(7, "Ada Lovelace"));
        assertEquals(PromotionSchemas_AccountV2Intrabuf.OLDEST_MAJOR, 2);
        assertEquals(Reason.BELOW_OLDEST, assertThrows(IncompatiblePayloadException.class,
                () -> PromotionSchemas_AccountV2Intrabuf.fromBytes(v1)).reason());
    }
}
