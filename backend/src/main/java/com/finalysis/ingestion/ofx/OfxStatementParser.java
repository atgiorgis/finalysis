package com.finalysis.ingestion.ofx;

import com.finalysis.account.AccountType;
import com.finalysis.ingestion.AccountHints;
import com.finalysis.ingestion.FormatMatch;
import com.finalysis.ingestion.ImportFile;
import com.finalysis.ingestion.ParseContext;
import com.finalysis.ingestion.ParsedStatement;
import com.finalysis.ingestion.ParsedTxn;
import com.finalysis.ingestion.SourceSection;
import com.finalysis.ingestion.StatementParseException;
import com.finalysis.ingestion.StatementParser;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads OFX 1.x/2.x and QFX bank and credit card statements, one parser for all institutions
 * (ING-01). Signs need no conversion: OFX amounts already use the app's convention (negative =
 * money out of the account) for both bank and card statements (ING-02).
 *
 * <p><b>Mapping.</b> A file must hold exactly one statement: {@code BANKMSGSRSV1/STMTTRNRS/STMTRS}
 * (bank) or {@code CREDITCARDMSGSRSV1/CCSTMTTRNRS/CCSTMTRS} (card), in {@code CURDEF} USD. Per
 * {@code STMTTRN}:
 * <ul>
 *   <li>{@code DTPOSTED} is the printed date (fingerprinted) and post date; {@code DTUSER}, when
 *       present, is the transaction date. Only the date part (first 8 digits) is read; time and
 *       time zone are ignored, because converting zones could move a row to another day and change
 *       its fingerprint.</li>
 *   <li>{@code TRNAMT} is stored as written (printed amount) and as a number; {@code FITID} is the
 *       external id; {@code CHECKNUM} the check number.</li>
 *   <li>The raw description is {@code NAME} and {@code MEMO} joined by one space when both are
 *       non-empty, or whichever one is non-empty. A MEMO that repeats NAME is kept: cleaning
 *       happens later, for the merchant field only. A row with neither is a parse error.</li>
 *   <li>The section comes from {@link #section}; the table is locked by a test because it feeds
 *       the fingerprint.</li>
 * </ul>
 * {@code BANKTRANLIST/DTSTART} and {@code DTEND} give the period, {@code LEDGERBAL/BALAMT} the
 * closing balance. OFX has no opening balance.
 *
 * <p><b>Account numbers.</b> Only the last four digits of {@code ACCTID} leave this class (as an
 * {@link AccountHints}). The full number is never stored, logged or put in a message, and the
 * {@code ACCTID} of a transfer's {@code BANKACCTTO}/{@code CCACCTTO} is masked in the source line
 * text.
 */
@Component
public class OfxStatementParser implements StatementParser {

    /** Types that mean money out; a positive amount with one of them gets a warning. */
    private static final Set<String> MONEY_OUT_TYPES = Set.of("DEBIT", "CHECK", "FEE", "SRVCHG", "ATM", "CASH", "DIRECTDEBIT");
    /** Types that mean money in. On cards, INT is interest charged, so it isn't one of them. */
    private static final Set<String> BANK_MONEY_IN_TYPES = Set.of("CREDIT", "DEP", "DIRECTDEP", "INT", "DIV");
    private static final Set<String> CARD_MONEY_IN_TYPES = Set.of("CREDIT", "DEP", "DIRECTDEP");
    private static final Map<String, AccountType> BANK_ACCOUNT_TYPES = Map.of(
            "CHECKING", AccountType.CHECKING, "SAVINGS", AccountType.SAVINGS);

    private static final Pattern DATE = Pattern.compile("([0-9]{4})([0-9]{2})([0-9]{2}).*", Pattern.DOTALL);
    private static final Pattern AMOUNT = Pattern.compile("[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)");
    private static final Pattern ACCOUNT_ID_VALUE = Pattern.compile("(<ACCTID>)([^<]*)");
    private static final Pattern CODE = Pattern.compile("[A-Z0-9.]{1,12}");

    private final OfxReader reader = new OfxReader();

    @Override
    public String id() {
        return "ofx";
    }

    @Override
    public FormatMatch detect(ImportFile file) {
        return OfxReader.hasOfxSignature(file.bytes()) ? FormatMatch.DEFINITE : FormatMatch.NONE;
    }

    @Override
    public ParsedStatement parse(ImportFile file, ParseContext context) {
        OfxDocument document = reader.read(file.bytes());
        StatementResponse response = singleStatement(document.root());
        OfxAggregate statement = response.statement();
        List<String> warnings = new ArrayList<>();

        String currency = required(statement, "CURDEF");
        if (!currency.equals("USD")) {
            throw new StatementParseException(statement.line(), "only USD statements are supported, not " + code(currency));
        }
        AccountHints hints = accountHints(statement, response.card(), warnings);

        OfxAggregate transactionList = statement.aggregates("BANKTRANLIST").stream().findFirst()
                .orElseThrow(() -> new StatementParseException(statement.line(), "statement has no <BANKTRANLIST>"));
        LocalDate periodStart = date(transactionList, "DTSTART");
        LocalDate periodEnd = date(transactionList, "DTEND");
        BigDecimal closing = statement.aggregates("LEDGERBAL").stream().findFirst()
                .map(ledger -> amount(ledger, "BALAMT", required(ledger, "BALAMT")))
                .orElse(null);

        List<ParsedTxn> txns = new ArrayList<>();
        for (OfxAggregate transaction : transactionList.aggregates("STMTTRN")) {
            txns.add(transaction(document, transaction, response.card(), warnings));
        }
        try {
            return new ParsedStatement(hints, periodStart, periodEnd, null, closing, Map.of(), txns, warnings);
        } catch (IllegalArgumentException e) {
            throw new StatementParseException(transactionList.line(), "invalid statement: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ statement

    private record StatementResponse(OfxAggregate statement, boolean card) {
    }

    private static StatementResponse singleStatement(OfxAggregate root) {
        List<StatementResponse> found = new ArrayList<>();
        collect(root, "BANKMSGSRSV1", "STMTTRNRS", "STMTRS", false, found);
        collect(root, "CREDITCARDMSGSRSV1", "CCSTMTTRNRS", "CCSTMTRS", true, found);
        if (found.isEmpty()) {
            throw new StatementParseException(null, "file has no bank or credit card statement");
        }
        if (found.size() > 1) {
            throw new StatementParseException(found.get(1).statement().line(),
                    "file has more than one statement; import one account per file");
        }
        return found.getFirst();
    }

    private static void collect(OfxAggregate root, String messageSet, String transactionResponse, String statement,
                                boolean card, List<StatementResponse> found) {
        for (OfxAggregate messages : root.aggregates(messageSet)) {
            for (OfxAggregate response : messages.aggregates(transactionResponse)) {
                checkStatus(response);
                response.aggregates(statement).forEach(s -> found.add(new StatementResponse(s, card)));
            }
        }
    }

    private static void checkStatus(OfxAggregate response) {
        for (OfxAggregate status : response.aggregates("STATUS")) {
            String code = status.leafValue("CODE").orElse("");
            if (!code.isEmpty() && !code.equals("0")) {
                throw new StatementParseException(status.line(), "the institution reported error " + code(code) + " for this statement");
            }
        }
    }

    private static AccountHints accountHints(OfxAggregate statement, boolean card, List<String> warnings) {
        String accountTag = card ? "CCACCTFROM" : "BANKACCTFROM";
        OfxAggregate account = statement.aggregates(accountTag).stream().findFirst()
                .orElseThrow(() -> new StatementParseException(statement.line(), "statement has no <" + accountTag + ">"));
        String accountId = required(account, "ACCTID");
        String lastFour = accountId.length() >= 4 ? accountId.substring(accountId.length() - 4) : "";
        if (!lastFour.matches("[0-9]{4}")) {
            warnings.add("line " + account.line() + ": the account number does not end in four digits");
            lastFour = null;
        }

        AccountType type = AccountType.CREDIT_CARD;
        if (!card) {
            String accountType = account.leafValue("ACCTTYPE").orElse("");
            type = BANK_ACCOUNT_TYPES.get(accountType);
            if (type == null) {
                warnings.add("line " + account.line() + ": ACCTTYPE " + code(accountType) + " is not mapped to an account type");
            }
        }
        return new AccountHints(lastFour, type, "USD");
    }

    // ------------------------------------------------------------------ transactions

    private static ParsedTxn transaction(OfxDocument document, OfxAggregate transaction, boolean card, List<String> warnings) {
        int line = transaction.line();
        String type = transaction.leafValue("TRNTYPE").orElse("");
        LocalDate posted = date(transaction, "DTPOSTED");
        LocalDate user = nonEmpty(transaction.leafValue("DTUSER")).isPresent() ? date(transaction, "DTUSER") : posted;
        String printedAmount = required(transaction, "TRNAMT");
        BigDecimal amount = amount(transaction, "TRNAMT", printedAmount);

        if (contradicts(type, amount, card)) {
            String direction = amount.signum() > 0 ? "positive" : "negative";
            warnings.add("line " + line + ": TRNTYPE " + code(type) + " with a " + direction + " amount");
        }
        try {
            return new ParsedTxn(posted, user, posted, description(transaction), printedAmount, amount,
                    section(card, type, amount),
                    nonEmpty(transaction.leafValue("CHECKNUM")).orElse(null),
                    nonEmpty(transaction.leafValue("FITID")).orElse(null),
                    line, maskAccountIds(document.sourceText(transaction)));
        } catch (IllegalArgumentException e) {
            throw new StatementParseException(line, "invalid transaction: " + e.getMessage());
        }
    }

    /** NAME and MEMO joined by one space when both are non-empty (see class Javadoc). */
    static String description(OfxAggregate transaction) {
        Optional<String> name = nonEmpty(transaction.leafValue("NAME"))
                .or(() -> transaction.aggregates("PAYEE").stream().findFirst().flatMap(payee -> nonEmpty(payee.leafValue("NAME"))));
        Optional<String> memo = nonEmpty(transaction.leafValue("MEMO"));
        if (name.isPresent() && memo.isPresent()) {
            return name.get() + " " + memo.get();
        }
        return name.or(() -> memo)
                .orElseThrow(() -> new StatementParseException(transaction.line(), "transaction has neither NAME nor MEMO"));
    }

    /**
     * Statement section for a row. Feeds the fingerprint, so changing any mapping here changes
     * fingerprints and needs a data migration (see {@code TransactionFingerprint}).
     * <ul>
     *   <li>Bank: CHECK → CHECK; FEE, SRVCHG → FEE; INT → INTEREST; otherwise a negative amount
     *       is a WITHDRAWAL and zero or positive a DEPOSIT.</li>
     *   <li>Card: FEE, SRVCHG → FEE; INT → INTEREST; otherwise a negative amount is a PURCHASE and
     *       zero or positive a PAYMENT (which, as on printed card statements, includes refunds).</li>
     * </ul>
     */
    static SourceSection section(boolean card, String type, BigDecimal amount) {
        switch (type) {
            case "FEE", "SRVCHG" -> {
                return SourceSection.FEE;
            }
            case "INT" -> {
                return SourceSection.INTEREST;
            }
            case "CHECK" -> {
                if (!card) {
                    return SourceSection.CHECK;
                }
            }
            default -> {
            }
        }
        boolean moneyOut = amount.signum() < 0;
        if (card) {
            return moneyOut ? SourceSection.PURCHASE : SourceSection.PAYMENT;
        }
        return moneyOut ? SourceSection.WITHDRAWAL : SourceSection.DEPOSIT;
    }

    /** True if TRNTYPE clearly means one direction and the amount goes the other. Zero never contradicts. */
    static boolean contradicts(String type, BigDecimal amount, boolean card) {
        if (amount.signum() > 0) {
            return MONEY_OUT_TYPES.contains(type);
        }
        if (amount.signum() < 0) {
            return (card ? CARD_MONEY_IN_TYPES : BANK_MONEY_IN_TYPES).contains(type);
        }
        return false;
    }

    /** Replaces every ACCTID value in the text with {@code ****} plus its last four characters. */
    static String maskAccountIds(String text) {
        Matcher matcher = ACCOUNT_ID_VALUE.matcher(text);
        StringBuilder masked = new StringBuilder();
        while (matcher.find()) {
            String value = matcher.group(2);
            String id = value.strip();
            String mask = "****" + (id.length() > 4 ? id.substring(id.length() - 4) : "");
            int start = value.indexOf(id);
            String replacement = value.substring(0, start) + mask + value.substring(start + id.length());
            matcher.appendReplacement(masked, Matcher.quoteReplacement(matcher.group(1) + replacement));
        }
        matcher.appendTail(masked);
        return masked.toString();
    }

    // ------------------------------------------------------------------ values

    private static String required(OfxAggregate parent, String tag) {
        return nonEmpty(parent.leafValue(tag))
                .orElseThrow(() -> new StatementParseException(parent.line(), "<" + parent.name() + "> has no <" + tag + ">"));
    }

    private static LocalDate date(OfxAggregate parent, String tag) {
        Matcher matcher = DATE.matcher(required(parent, tag));
        if (matcher.matches()) {
            try {
                return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)));
            } catch (DateTimeException e) {
                // fall through to the error below
            }
        }
        throw new StatementParseException(parent.line(), "invalid date in <" + tag + ">");
    }

    private static BigDecimal amount(OfxAggregate parent, String tag, String text) {
        if (!AMOUNT.matcher(text).matches()) {
            throw new StatementParseException(parent.line(), "invalid amount in <" + tag + ">");
        }
        return new BigDecimal(text);
    }

    private static Optional<String> nonEmpty(Optional<String> value) {
        return value.filter(v -> !v.isEmpty());
    }

    /** OFX codes such as TRNTYPE are format metadata; anything that doesn't look like one isn't echoed. */
    private static String code(String value) {
        return CODE.matcher(value).matches() ? value : "(invalid)";
    }
}
