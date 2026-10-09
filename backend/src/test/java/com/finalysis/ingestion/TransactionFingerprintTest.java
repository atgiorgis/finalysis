package com.finalysis.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.ingestion.TransactionFingerprint.Input;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionFingerprintTest {

    private static final long ACCOUNT = 7L;
    private static final LocalDate PRINTED = LocalDate.of(2026, 7, 6);

    private static Input row(String amount, String description, SourceSection section) {
        return new Input(ACCOUNT, PRINTED, new BigDecimal(amount), description, section);
    }

    private static Input coffee() {
        return row("-6.45", "CHECKCARD 0704 BLUE HERON COFFEE", SourceSection.WITHDRAWAL);
    }

    private static List<Input> statementRows() {
        return List.of(
                row("2451.08", "BRIGHTWAY LOGISTICS PAYROLL PPD ID: 4419920113", SourceSection.DEPOSIT),
                coffee(),
                row("-1850.00", "PARKSIDE PROPERTY MGMT RENT WEB PMT", SourceSection.WITHDRAWAL),
                coffee(),
                row("-12.00", "MONTHLY MAINTENANCE FEE", SourceSection.FEE));
    }

    @Test
    void sameInputsSameFingerprint() {
        String first = TransactionFingerprint.of(coffee(), 1);

        assertThat(TransactionFingerprint.of(coffee(), 1)).isEqualTo(first);
        assertThat(first).matches("[0-9a-f]{64}");
    }

    @Test
    void reprocessingStatementIsStable() {
        List<String> firstImport = TransactionFingerprint.forStatement(statementRows());
        List<String> reupload = TransactionFingerprint.forStatement(statementRows());

        assertThat(reupload).containsExactlyElementsOf(firstImport);
        assertThat(firstImport).doesNotHaveDuplicates();
    }

    @Test
    void duplicateChargeGetsDistinctFingerprints() {
        List<String> fingerprints = TransactionFingerprint.forStatement(List.of(coffee(), coffee()));

        assertThat(fingerprints).containsExactly(
                TransactionFingerprint.of(coffee(), 1),
                TransactionFingerprint.of(coffee(), 2));
        assertThat(fingerprints.get(0)).isNotEqualTo(fingerprints.get(1));
    }

    @Test
    void wrappedDescriptionEqualsSingleLine() {
        Input wrapped = row("-6.45", "CHECKCARD 0704 STARBUCKS\nSTORE 1234 SEATTLE WA", SourceSection.WITHDRAWAL);
        Input oneLine = row("-6.45", "CHECKCARD 0704 STARBUCKS STORE 1234 SEATTLE WA", SourceSection.WITHDRAWAL);

        assertThat(TransactionFingerprint.of(wrapped, 1)).isEqualTo(TransactionFingerprint.of(oneLine, 1));
    }

    @Test
    void caseAndWhitespaceIgnored() {
        Input messy = row("-6.45", "  checkcard   0704\tblue heron  Coffee ", SourceSection.WITHDRAWAL);

        assertThat(TransactionFingerprint.of(messy, 1)).isEqualTo(TransactionFingerprint.of(coffee(), 1));
    }

    @Test
    void amountScaleNormalizedButSignMatters() {
        String tenFifty = TransactionFingerprint.of(row("10.50", "REFUND", SourceSection.DEPOSIT), 1);

        assertThat(TransactionFingerprint.of(row("10.5", "REFUND", SourceSection.DEPOSIT), 1)).isEqualTo(tenFifty);
        assertThat(TransactionFingerprint.of(row("-10.50", "REFUND", SourceSection.DEPOSIT), 1)).isNotEqualTo(tenFifty);
    }

    @Test
    void sectionMatters() {
        Input deposit = row("25.00", "ZELLE TRANSFER", SourceSection.DEPOSIT);
        Input withdrawal = row("25.00", "ZELLE TRANSFER", SourceSection.WITHDRAWAL);

        assertThat(TransactionFingerprint.of(deposit, 1)).isNotEqualTo(TransactionFingerprint.of(withdrawal, 1));
    }

    /** What a parser might produce for one printed line: raw values plus values derived from them. */
    private record ParsedRow(LocalDate printedDate, BigDecimal amount, String rawDescription,
                             SourceSection section, String merchant, LocalDate txnDate) {

        Input toFingerprintInput(long accountId) {
            return new Input(accountId, printedDate, amount, rawDescription, section);
        }
    }

    @Test
    void derivedFieldsDoNotAffectFingerprint() {
        BigDecimal amount = new BigDecimal("-6.45");
        String raw = "CHECKCARD 0704 BLUE HERON COFFEE";
        // Parser v1 missed the embedded purchase date; v2 extracts it and cleans the merchant.
        ParsedRow v1 = new ParsedRow(PRINTED, amount, raw, SourceSection.WITHDRAWAL,
                "CHECKCARD 0704 BLUE HERON COFFEE", PRINTED);
        ParsedRow v2 = new ParsedRow(PRINTED, amount, raw, SourceSection.WITHDRAWAL,
                "Blue Heron Coffee", LocalDate.of(2026, 7, 4));

        assertThat(TransactionFingerprint.of(v2.toFingerprintInput(ACCOUNT), 1))
                .isEqualTo(TransactionFingerprint.of(v1.toFingerprintInput(ACCOUNT), 1));
    }

    @Test
    void inputHoldsOnlyRawFields() {
        List<String> components = Arrays.stream(Input.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(components)
                .as("Only raw, as-printed values may be fingerprinted; see TransactionFingerprint Javadoc")
                .containsExactly("accountId", "printedDate", "amount", "rawDescription", "sourceSection");
    }

    @Test
    void occurrenceCountsNormalizedRows() {
        Input lower = row("10.5", "coffee", SourceSection.WITHDRAWAL);
        Input upper = row("10.50", "COFFEE", SourceSection.WITHDRAWAL);

        assertThat(TransactionFingerprint.forStatement(List.of(lower, upper))).containsExactly(
                TransactionFingerprint.of(upper, 1),
                TransactionFingerprint.of(upper, 2));
    }

    @Test
    void rejectsOccurrenceBelowOne() {
        assertThatThrownBy(() -> TransactionFingerprint.of(coffee(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAmountWithMoreThanTwoDecimals() {
        assertThatThrownBy(() -> TransactionFingerprint.of(row("10.505", "COFFEE", SourceSection.WITHDRAWAL), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10.505");
    }

    @Test
    void rejectsRowsFromMoreThanOneAccount() {
        Input other = new Input(ACCOUNT + 1, PRINTED, new BigDecimal("-6.45"), "COFFEE", SourceSection.WITHDRAWAL);

        assertThatThrownBy(() -> TransactionFingerprint.forStatement(List.of(coffee(), other)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
