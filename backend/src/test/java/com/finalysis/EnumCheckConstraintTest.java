package com.finalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.account.AccountType;
import com.finalysis.categorize.CategoryKind;
import com.finalysis.categorize.CategorySource;
import com.finalysis.categorize.RuleMatchType;
import com.finalysis.categorize.RuleSource;
import com.finalysis.ingestion.SourceSection;
import com.finalysis.ingestion.StatementStatus;
import com.finalysis.ingestion.TransferMethod;
import com.finalysis.ingestion.TransferStatus;
import com.finalysis.support.IntegrationTest;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Every enum mapped with EnumType.STRING has exactly the values its CHECK constraint allows. */
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
                Arguments.of("ck_category_kind", CategoryKind.class),
                Arguments.of("ck_txn_source_section", SourceSection.class),
                Arguments.of("ck_txn_category_source", CategorySource.class),
                Arguments.of("ck_categorization_rule_match_type", RuleMatchType.class),
                Arguments.of("ck_categorization_rule_source", RuleSource.class),
                Arguments.of("ck_transfer_link_method", TransferMethod.class),
                Arguments.of("ck_transfer_link_status", TransferStatus.class));
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
