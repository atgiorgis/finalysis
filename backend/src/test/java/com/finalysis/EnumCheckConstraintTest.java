package com.finalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountType;
import com.finalysis.alert.AlertType;
import com.finalysis.categorize.AiCategorizer;
import com.finalysis.categorize.CategoryKind;
import com.finalysis.categorize.CategorySource;
import com.finalysis.categorize.RuleDirection;
import com.finalysis.categorize.RuleField;
import com.finalysis.categorize.RuleMatchType;
import com.finalysis.categorize.RuleSource;
import com.finalysis.ingestion.BalanceCheck;
import com.finalysis.ingestion.SourceSection;
import com.finalysis.ingestion.StatementStatus;
import com.finalysis.ingestion.UnparsedUploadResolution;
import com.finalysis.ingestion.csv.CsvAmountMode;
import com.finalysis.ingestion.csv.CsvSign;
import com.finalysis.support.IntegrationTest;
import com.finalysis.transfer.TransferMethod;
import com.finalysis.transfer.TransferStatus;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every enum mapped with EnumType.STRING has exactly the values its CHECK constraint allows, and so does every enum
 * read from such a column in plain SQL (analytics has its own {@code CategoryKind}, docs/plans/phase-5.md §3.2).
 */
@IntegrationTest
class EnumCheckConstraintTest {

    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    private final JdbcTemplate jdbc;

    EnumCheckConstraintTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static Stream<Arguments> enums() {
        return Stream.of(
                Arguments.of("ck_account_type", AccountType.class),
                Arguments.of("ck_statement_status", StatementStatus.class),
                Arguments.of("ck_statement_balance_check", BalanceCheck.class),
                Arguments.of("ck_category_kind", CategoryKind.class),
                Arguments.of("ck_category_kind", com.finalysis.analytics.CategoryKind.class),
                Arguments.of("ck_txn_source_section", SourceSection.class),
                Arguments.of("ck_txn_category_source", CategorySource.class),
                Arguments.of("ck_txn_ai_review_reason", AiCategorizer.ReviewReason.class),
                Arguments.of("ck_categorization_rule_match_type", RuleMatchType.class),
                Arguments.of("ck_categorization_rule_source", RuleSource.class),
                Arguments.of("ck_categorization_rule_field", RuleField.class),
                Arguments.of("ck_categorization_rule_direction", RuleDirection.class),
                Arguments.of("ck_transfer_link_method", TransferMethod.class),
                Arguments.of("ck_transfer_link_status", TransferStatus.class),
                Arguments.of("ck_csv_mapping_amount_mode", CsvAmountMode.class),
                Arguments.of("ck_csv_mapping_sign", CsvSign.class),
                Arguments.of("ck_unparsed_upload_resolution_kind", UnparsedUploadResolution.class),
                Arguments.of("ck_alert_dismissal_type", AlertType.class),
                Arguments.of("ck_chat_turn_answer_source", com.finalysis.chat.AnswerSource.class),
                Arguments.of("ck_chat_turn_grounding", com.finalysis.chat.grounding.GroundingStatus.class),
                Arguments.of("ck_chat_turn_limit", com.finalysis.chat.LimitHit.class),
                Arguments.of("ck_chat_tool_call_status", com.finalysis.chat.ToolStatus.class),
                Arguments.of("ck_chat_usage_kind", com.finalysis.chat.UsageKind.class));
    }

    @ParameterizedTest
    @MethodSource("enums")
    void enumMatchesCheckConstraint(String constraint, Class<? extends Enum<?>> enumType) {
        String definition = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class, constraint);

        List<String> allowed = QUOTED.matcher(definition).results().map(m -> m.group(1)).toList();
        List<String> constants = Arrays.stream(enumType.getEnumConstants()).map(Enum::name).toList();

        assertThat(constants).as(constraint).containsExactlyInAnyOrderElementsOf(allowed);
    }
}
