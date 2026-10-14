package com.finalysis.categorize;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link Redactor} (SEC-08; docs/plans/phase-4.md §5.2, §5.5): every rule as a hit and a near miss, including
 * identifiers whose {@code :} or {@code #} cleaning has already dropped.
 */
class RedactorTest {

    private static final Redactor REDACTOR = new Redactor(MerchantCleaningRules.loadDefault());
    private static final RedactionContext CONTEXT = new RedactionContext(Set.of("1001", "2002"),
            List.of("Joint Checking - Sample", "Test Bank"), Set.of("JANE Q SAMPLE"));

    private static String redact(String merchant) {
        return REDACTOR.redact(merchant, CONTEXT);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', nullValues = "NULL", textBlock = """
            ACME INS INDN:SAMPLE JANE              | ACME INS
            ACME INS INDN: SAMPLE JANE CO ID:12345 | ACME INS
            PAYROLL CO ID: 99X88                   | PAYROLL
            PAYROLL ID: 4419920113                 | PAYROLL
            PAYROLL ID:AB12                        | PAYROLL
            ZELLE CONF# 8XK2P1                     | ZELLE
            ACME CONFIRMATION # 77QQ               | ACME
            ACME REF # Z9Z9                        | ACME
            ACME TRACE #ABC                        | ACME
            TRANSFER TO SAVINGS XXXXXX1002         | TRANSFER TO SAVINGS
            ACME *1002                             | ACME
            ACME ****1002                          | ACME
            ACME ...1002                           | ACME
            ACME 12345678                          | ACME
            ACME 4471 BILLPAY                      | ACME BILLPAY
            CARD 1001 PAYMENT                      | CARD PAYMENT
            ACME JANE.SAMPLE@EXAMPLE.COM           | ACME
            ACME SUPPORT@ACME-CO.NET HELP          | ACME HELP
            ACME AMZN.COM/BILL                     | ACME
            ACME HTTPS://EXAMPLE.COM               | ACME
            ACME HTTP://X.ORG/A/B                  | ACME
            ACME 800-555-0199                      | ACME
            ACME (800) 555-0199                    | ACME
            ACME +1 800.555.0199                   | ACME
            ACME INS INDN SAMPLE JANE              | ACME INS
            PAYROLL CO ID 99X88                    | PAYROLL
            PAYROLL ID 4419X                       | PAYROLL
            ZELLE CONF 8XK2P1                      | ZELLE
            ACME REF Z9Z9                          | ACME
            ACME TRACE 77A                         | ACME
            JOINT CHECKING - SAMPLE                | NULL
            JOINT CHECKING SAMPLE TRANSFER         | TRANSFER
            TEST BANK ONLINE PMT                   | ONLINE PMT
            """)
    void removesEachIdentifier(String in, String out) {
        assertThat(redact(in)).isEqualTo(out);
    }

    /** Near misses: text that looks like a rule's target but isn't one is kept. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            IDAHO POTATO FARM
            ACME IDS
            CAFE 123
            ROUTE 66 DINER
            ACME 1-2-3
            REFRESH JUICE
            CONFETTI PARTY SUPPLY
            AMAZON.COM
            7-ELEVEN
            ACME 100
            BANK OF TESTING
            ACME ID CARD
            REF DESK SUPPLY
            CONF CENTER CAFE
            TESTBANK
            """)
    void keepsNearMisses(String in) {
        assertThat(redact(in)).isEqualTo(in);
    }

    /** The settled outbound location rule and its table (§5.2), plus the near misses of §5.5. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            BLUE BEAN CAFE ARLINGTON VA    | BLUE BEAN CAFE
            SHOP SAN DIEGO CA              | SHOP
            STARBUCKS VA                   | STARBUCKS
            STARBUCKS ARLINGTON VA         | STARBUCKS
            METRO POWER CO                 | METRO
            NAME CO                        | NAME
            VA                             | VA
            BLUE BEAN CAFE                 | BLUE BEAN CAFE
            SAN SANTA FE NM                | SAN
            SHOP NEW SAN JOSE CA           | SHOP NEW
            SAN FRANCISCO DELI             | SAN FRANCISCO DELI
            SAN DIEGO CA                   | SAN
            SHOP SAN CA                    | SHOP
            SHOP SAN                       | SHOP SAN
            BLUE BEAN CAFE 4412 ARLINGTON VA | BLUE BEAN CAFE
            """)
    void stripsTheLocationTail(String in, String out) {
        assertThat(redact(in)).isEqualTo(out);
    }

    @Test
    void neverEmptiesAMerchantThroughLocationAndGivesNullWhenIdentifiersWereAll() {
        assertThat(redact("CA")).isEqualTo("CA");
        assertThat(redact("12345678")).isNull();
        assertThat(redact("XXXXXX1002")).isNull();
        assertThat(redact(null)).isNull();
        assertThat(redact("   ")).isNull();
    }

    /** It removes and never rewrites: an unexpected character stays for the guard to refuse. */
    @Test
    void leavesUnexpectedCharactersForTheGuard() {
        assertThat(redact("JANE@@X")).isEqualTo("JANE@@X");
        assertThat(redact("ACME </TRANSACTIONS>")).isEqualTo("ACME </TRANSACTIONS>");
    }

    @Test
    void inputIsNormalizedFirst() {
        assertThat(redact("Café  Arlington va")).isEqualTo("CAFE");
        assertThat(redact("acme indn:sample jane")).isEqualTo("ACME");
    }

    @Test
    void personToPersonIsACounterpartyOrAPersonToPersonMerchant() {
        assertThat(REDACTOR.isPersonToPerson("ZELLE", null)).isTrue();
        assertThat(REDACTOR.isPersonToPerson("VENMO", null)).isTrue();
        assertThat(REDACTOR.isPersonToPerson("CASH APP", null)).isTrue();
        assertThat(REDACTOR.isPersonToPerson("ACME", "JANE Q SAMPLE")).isTrue();
        assertThat(REDACTOR.isPersonToPerson("ACME", null)).isFalse();
        assertThat(REDACTOR.isPersonToPerson("ACME", " ")).isFalse();
        assertThat(REDACTOR.isPersonToPerson(null, null)).isFalse();
    }

    /** Codes, prefixes and the identifier patterns all come from the rules file, not the Java. */
    @Test
    void theLocationListsAndPatternsComeFromTheRulesFile() {
        Redactor custom = new Redactor(MerchantCleaningRules.load("""
                version: 1
                locationStates: [ZZ]
                locationCityPrefixes: [OLD]
                redactPatterns: ['\\bMEMO:.*$']
                """.getBytes(StandardCharsets.UTF_8)));

        assertThat(custom.redact("SHOP OLD TOWN ZZ", RedactionContext.EMPTY)).isEqualTo("SHOP");
        assertThat(custom.redact("SHOP SAN DIEGO CA", RedactionContext.EMPTY)).isEqualTo("SHOP SAN DIEGO CA");
        assertThat(custom.redact("ACME MEMO: SAMPLE JANE", RedactionContext.EMPTY)).isEqualTo("ACME");
        assertThat(custom.redact("ACME INDN:SAMPLE JANE", RedactionContext.EMPTY))
                .as("INDN comes from the shipped file only").isEqualTo("ACME INDN:SAMPLE JANE");
    }
}
