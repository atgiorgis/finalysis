package com.finalysis.transaction;

import com.finalysis.account.AccountType;
import com.finalysis.api.ApiMoney;
import com.finalysis.categorize.CategoryResponse;
import com.finalysis.categorize.CategorySource;
import com.finalysis.ingestion.SourceSection;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One txn as the transaction API shows it (docs/plans/phase-4.md §9.2): the transfer API's {@code TxnSummary}
 * fields, plus the merchant, the category and where it came from, and the transfer flags. Money is a string
 * ({@link ApiMoney}). The raw description and counterparty are the user's own data on a local API; logs and
 * problem responses never carry them (SEC-10), and the counterparty is never sent to a model.
 *
 * @param category         null while uncategorized
 * @param ruleId           the rule that set a {@code RULE} category; null otherwise
 * @param categorizedAt    when the current category was set; null while uncategorized
 * @param categoryModel    the model behind an {@code AI} category; null otherwise
 * @param categoryConfidence the model's confidence in an {@code AI} category, 0 to 1; null otherwise
 * @param categoryPromptVersion the prompt version behind an {@code AI} category; null otherwise
 * @param aiReviewReason   why the AI left an uncategorized txn for review ({@code LOW_CONFIDENCE},
 *                         {@code INVALID_ANSWER}); null otherwise
 * @param isTransfer       on a transfer link (kept by the V9 trigger)
 * @param transferExcluded the user said it is not a transfer
 */
public record TransactionResponse(long id, long accountId, String accountName, AccountType accountType,
                                  long statementId, LocalDate txnDate, LocalDate postDate,
                                  @ApiMoney BigDecimal amount, SourceSection sourceSection, String rawDescription,
                                  String merchant, String counterparty, CategoryResponse category,
                                  CategorySource categorySource, Long ruleId, OffsetDateTime categorizedAt,
                                  String categoryModel, BigDecimal categoryConfidence, String categoryPromptVersion,
                                  String aiReviewReason, boolean isTransfer, boolean transferExcluded) {
}
