package com.finalysis.transaction;

import com.finalysis.account.AccountType;
import com.finalysis.categorize.CategoryKind;
import com.finalysis.categorize.CategoryResponse;
import com.finalysis.categorize.CategorySource;
import com.finalysis.ingestion.SourceSection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads txns for the transaction API: plain SQL, read-only. Newest {@code txn_date} first, then newest id, so
 * pages are stable.
 */
@Component
class TransactionQuery {

    private static final String SELECT = """
            SELECT t.id, t.account_id, a.name AS account_name, a.type AS account_type, t.statement_id, t.txn_date,
                   t.post_date, t.amount, t.source_section, t.raw_description, t.merchant, t.counterparty,
                   t.category_id, c.name AS category_name, c.kind AS category_kind, c.parent_id AS category_parent_id,
                   t.category_source, t.category_rule_id, t.categorized_at, t.category_model, t.category_confidence,
                   t.category_prompt_version, t.ai_review_reason, t.is_transfer, t.transfer_excluded,
                   s.period_start, s.period_end, t.source_line_number, t.source_line_text
            FROM txn t
            JOIN account a ON a.id = t.account_id
            JOIN statement s ON s.id = t.statement_id
            LEFT JOIN category c ON c.id = t.category_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    TransactionQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<TransactionResponse> find(long id) {
        return jdbc.query(SELECT + "WHERE t.id = :id", new MapSqlParameterSource("id", id), TransactionQuery::row)
                .stream().findFirst();
    }

    /** Call in one transaction, so the total and the page come from the same snapshot. */
    TransactionPage list(TransactionFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, params);
        long total = jdbc.queryForObject("SELECT count(*) FROM txn t" + where, params, Long.class);
        params.addValue("limit", filter.size()).addValue("offset", (long) filter.page() * filter.size());
        List<TransactionResponse> items = jdbc.query(
                SELECT + where + " ORDER BY t.txn_date DESC, t.id DESC LIMIT :limit OFFSET :offset", params,
                TransactionQuery::row);
        return new TransactionPage(items, filter.page(), filter.size(), total);
    }

    private static String where(TransactionFilter filter, MapSqlParameterSource params) {
        List<String> conditions = new ArrayList<>();
        if (filter.accountId() != null) {
            conditions.add("t.account_id = :accountId");
            params.addValue("accountId", filter.accountId());
        }
        if (filter.categoryId() != null) {
            conditions.add("t.category_id = :categoryId");
            params.addValue("categoryId", filter.categoryId());
        }
        if (filter.uncategorized() != null) {
            conditions.add(filter.uncategorized() ? "t.category_id IS NULL" : "t.category_id IS NOT NULL");
        }
        if (filter.source() != null) {
            conditions.add("t.category_source = :source");
            params.addValue("source", filter.source().name());
        }
        if (filter.isTransfer() != null) {
            conditions.add("t.is_transfer = :isTransfer");
            params.addValue("isTransfer", filter.isTransfer());
        }
        if (filter.from() != null) {
            conditions.add("t.txn_date >= :from");
            params.addValue("from", filter.from());
        }
        if (filter.to() != null) {
            conditions.add("t.txn_date <= :to");
            params.addValue("to", filter.to());
        }
        if (filter.merchant() != null) {
            conditions.add("t.merchant = :merchant");
            params.addValue("merchant", filter.merchant());
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private static TransactionResponse row(ResultSet rs, int rowNum) throws SQLException {
        Long categoryId = rs.getObject("category_id", Long.class);
        CategoryResponse category = categoryId == null ? null : new CategoryResponse(categoryId,
                rs.getString("category_name"), CategoryKind.valueOf(rs.getString("category_kind")),
                rs.getObject("category_parent_id", Long.class));
        String source = rs.getString("category_source");
        return new TransactionResponse(rs.getLong("id"), rs.getLong("account_id"), rs.getString("account_name"),
                AccountType.valueOf(rs.getString("account_type")), rs.getLong("statement_id"),
                rs.getObject("txn_date", LocalDate.class), rs.getObject("post_date", LocalDate.class),
                rs.getBigDecimal("amount"), SourceSection.valueOf(rs.getString("source_section")),
                rs.getString("raw_description"), rs.getString("merchant"), rs.getString("counterparty"), category,
                source == null ? null : CategorySource.valueOf(source), rs.getObject("category_rule_id", Long.class),
                rs.getObject("categorized_at", OffsetDateTime.class), rs.getString("category_model"),
                rs.getBigDecimal("category_confidence"), rs.getString("category_prompt_version"),
                rs.getString("ai_review_reason"), rs.getBoolean("is_transfer"), rs.getBoolean("transfer_excluded"),
                new TransactionResponse.Statement(rs.getLong("statement_id"),
                        rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class)),
                TransactionResponse.SourceLine.of(rs.getObject("source_line_number", Integer.class),
                        rs.getString("source_line_text")));
    }
}
