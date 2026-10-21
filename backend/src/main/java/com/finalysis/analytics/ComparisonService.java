package com.finalysis.analytics;

import com.finalysis.analytics.BucketTotalsQuery.Cell;
import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compares a measure between two periods (docs/plans/phase-6.md §3.3), so chat never computes a difference or a
 * percent change (GR-01). Each side's value comes from the same bucket query and the same rules as the trends' lines;
 * Java subtracts and divides ({@link Arithmetic#percent}, {@code HALF_EVEN}). Read-only, one REPEATABLE READ snapshot,
 * no endpoint: Phase 6's {@code compare_periods} tool is its one caller.
 */
@Service
public class ComparisonService {

    private final CoverageQuery coverage;
    private final BucketTotalsQuery buckets;
    private final ReferenceQuery references;
    private final AnalyticsProperties properties;

    public ComparisonService(CoverageQuery coverage, BucketTotalsQuery buckets, ReferenceQuery references,
                             AnalyticsProperties properties) {
        this.coverage = coverage;
        this.buckets = buckets;
        this.references = references;
        this.properties = properties;
    }

    /**
     * @param categoryId required for the category measures, refused for the others
     * @param accountId  one account's txns and its own coverage; empty = the household
     * @param a          the period compared, at most {@code max-months} long
     * @param b          the period compared with, at most {@code max-months} long
     * @throws InvalidRequestException for a missing or unexpected category, or a range that is too long
     * @throws com.finalysis.api.NotFoundException for an unknown account or category
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ComparisonResponse compare(ComparisonMeasure measure, Optional<Long> categoryId, Optional<Long> accountId,
                                      MonthRange a, MonthRange b) {
        validate(measure, categoryId, a, b);
        accountId.ifPresent(references::requireAccount);
        categoryId.ifPresent(references::requireCategory);
        CoverageMap map = coverage.load();
        ComparisonResponse.Side sideA = side(measure, categoryId, accountId, a, map);
        ComparisonResponse.Side sideB = side(measure, categoryId, accountId, b, map);
        BigDecimal difference = sideA.value().subtract(sideB.value());
        return new ComparisonResponse(measure, categoryId.map(id -> references.categories().get(id)).orElse(null),
                accountId.orElse(null), sideA, sideB, difference, Arithmetic.percent(difference, sideB.value().abs()));
    }

    private void validate(ComparisonMeasure measure, Optional<Long> categoryId, MonthRange a, MonthRange b) {
        List<InvalidField> errors = new ArrayList<>();
        if (measure.needsCategory() && categoryId.isEmpty()) {
            errors.add(new InvalidField("categoryId", "is required for " + measure));
        }
        if (!measure.needsCategory() && categoryId.isPresent()) {
            errors.add(new InvalidField("categoryId", "can't be combined with " + measure));
        }
        if (a.length() > properties.maxMonths()) {
            errors.add(new InvalidField("a", "must be at most " + properties.maxMonths() + " months"));
        }
        if (b.length() > properties.maxMonths()) {
            errors.add(new InvalidField("b", "must be at most " + properties.maxMonths() + " months"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
    }

    private ComparisonResponse.Side side(ComparisonMeasure measure, Optional<Long> categoryId, Optional<Long> accountId,
                                         MonthRange range, CoverageMap map) {
        BigDecimal value = Arithmetic.ZERO;
        for (Cell cell : buckets.find(range, accountId, categoryId)) {
            value = value.add(contribution(measure, cell));
        }
        List<ComparisonResponse.MonthCoverage> months = range.months().stream()
                .map(month -> new ComparisonResponse.MonthCoverage(month,
                        MonthTotalsReader.coverage(map, month, accountId)))
                .toList();
        return new ComparisonResponse.Side(range, value, months);
    }

    /** A cell's share of the measure, by the trends' rules (TrendsService): magnitudes for spending, signed for net. */
    private static BigDecimal contribution(ComparisonMeasure measure, Cell cell) {
        BigDecimal spent = switch (cell.bucket()) {
            case EXPENSE, REFUND -> cell.net().negate();
            case UNCATEGORIZED -> cell.moneyOut().negate();
            default -> Arithmetic.ZERO;
        };
        BigDecimal received = switch (cell.bucket()) {
            case INCOME -> cell.net();
            case UNCATEGORIZED -> cell.moneyIn();
            default -> Arithmetic.ZERO;
        };
        return switch (measure) {
            case SPENDING -> spent;
            case INCOME -> received;
            case NET -> received.subtract(spent);
            case CATEGORY_SPENT -> cell.bucket() == TxnBucket.EXPENSE ? cell.net().negate() : Arithmetic.ZERO;
            case CATEGORY_RECEIVED -> cell.bucket() == TxnBucket.INCOME || cell.bucket() == TxnBucket.REFUND
                    ? cell.net() : Arithmetic.ZERO;
        };
    }
}
