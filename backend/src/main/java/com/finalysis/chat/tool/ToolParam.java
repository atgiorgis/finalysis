package com.finalysis.chat.tool;

import com.finalysis.analytics.MonthRange;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One parameter of a tool (docs/plans/phase-6.md §3.1). Its type writes the JSON schema the model sees and parses the
 * value the model sends, so the two can't disagree. Parsing never repairs: a value is accepted as the schema describes
 * it, or it is a {@link FieldProblem}.
 *
 * @param name        the property name
 * @param type        what it holds
 * @param required    whether the schema requires it; an optional parameter sent as {@code null} counts as left out
 * @param description what the model reads, with units
 */
public record ToolParam(String name, Type type, boolean required, String description) {

    /** The longest month range a tool accepts, both ends included (the analytics' {@code max-months} default). */
    public static final int MAX_MONTHS = 24;

    /** The longest text a name argument may have. */
    static final int MAX_TEXT = 100;

    static final Pattern MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    public static ToolParam optional(String name, Type type, String description) {
        return new ToolParam(name, type, false, description);
    }

    public static ToolParam required(String name, Type type, String description) {
        return new ToolParam(name, type, true, description);
    }

    /** A parsed value, or the problem with it. */
    public record Parsed(Object value, FieldProblem problem) {

        static Parsed ok(Object value) {
            return new Parsed(value, null);
        }

        static Parsed problem(String name, ToolProblem problem) {
            return new Parsed(null, new FieldProblem(name, problem));
        }
    }

    /** What a parameter holds. */
    public sealed interface Type permits Month, Alias, Category, Choice, WholeNumber, Flag, Merchant, Merchants, Search,
            Period {

        /** This type's part of the JSON schema. */
        void schema(ObjectNode property);

        Parsed parse(String name, JsonNode value, ToolScope scope);
    }

    /** A month, {@code yyyy-MM}; parsed to a {@link YearMonth}. */
    public record Month() implements Type {

        @Override
        public void schema(ObjectNode property) {
            property.put("type", "string").put("pattern", MONTH.pattern());
        }

        @Override
        public Parsed parse(String name, JsonNode value, ToolScope scope) {
            return month(value).map(Parsed::ok).orElseGet(() -> Parsed.problem(name, ToolProblem.NOT_A_MONTH));
        }
    }

    /** An account alias of the context block; parsed to the account id. */
    public record Alias() implements Type {

        @Override
        public void schema(ObjectNode property) {
            property.put("type", "string").put("maxLength", 20);
        }

        @Override
        public Parsed parse(String name, JsonNode value, ToolScope scope) {
            if (!value.isString()) {
                return Parsed.problem(name, ToolProblem.NOT_TEXT);
            }
            return scope.accountId(value.stringValue()).map(Parsed::ok)
                    .orElseGet(() -> Parsed.problem(name, ToolProblem.NOT_AN_ACCOUNT));
        }
    }

    /** A category name of the context block, exactly; parsed to the category id. */
    public record Category() implements Type {

        @Override
        public void schema(ObjectNode property) {
            property.put("type", "string").put("maxLength", MAX_TEXT);
        }

        @Override
        public Parsed parse(String name, JsonNode value, ToolScope scope) {
            if (!value.isString()) {
                return Parsed.problem(name, ToolProblem.NOT_TEXT);
            }
            return scope.categoryId(value.stringValue()).map(Parsed::ok)
                    .orElseGet(() -> Parsed.problem(name, ToolProblem.NOT_A_CATEGORY));
        }
    }

    /** One constant of an enum, by its exact name; parsed to the constant. */
    public record Choice(Class<? extends Enum<?>> values) implements Type {

        public List<String> names() {
            return Arrays.stream(values.getEnumConstants()).map(Enum::name).toList();
        }

        @Override
        public void schema(ObjectNode property) {
            ArrayNode values = property.put("type", "string").putArray("enum");
            names().forEach(values::add);
        }

        @Override
        public Parsed parse(String name, JsonNode value, ToolScope scope) {
            if (!value.isString()) {
                return Parsed.problem(name, ToolProblem.NOT_TEXT);
            }
            return Arrays.stream(values.getEnumConstants()).filter(c -> c.name().equals(value.stringValue()))
                    .findFirst().map(c -> Parsed.ok((Object) c))
                    .orElseGet(() -> Parsed.problem(name, ToolProblem.NOT_A_LISTED_VALUE));
        }
    }

    /** A whole number from {@code min} to {@code max}; parsed to an {@link Integer}. */
    public record WholeNumber(int min, int max) implements Type {

        @Override
        public void schema(ObjectNode property) {
            property.put("type", "integer").put("minimum", min).put("maximum", max);
        }

        @Override
        public Parsed parse(String name, JsonNode value, ToolScope scope) {
            if (!value.isIntegralNumber()) {
                return Parsed.problem(name, ToolProblem.NOT_A_WHOLE_NUMBER);
            }
            if (!value.canConvertToInt()) {
                return Parsed.problem(name, ToolProblem.OUT_OF_BOUNDS);
            }
            int number = value.intValue();
            return number < min || number > max ? Parsed.problem(name, ToolProblem.OUT_OF_BOUNDS)
                    : Parsed.ok(number);
        }
    }

    /** True or false. */
    public record Flag() implements Type {

        @Override
        public void schema(ObjectNode property) {
            property.put("type", "boolean");
        }

        @Override
        public Parsed parse(String name, JsonNode value, ToolScope scope) {
            return value.isBoolean() ? Parsed.ok(value.booleanValue())
                    : Parsed.problem(name, ToolProblem.NOT_TRUE_OR_FALSE);
        }
    }

    /**
     * A merchant exactly as results show it (docs/plans/phase-6.md §4.3); parsed to the one stored merchant behind it.
     * The withheld placeholder, a text no merchant goes out as, and a text that stands for several stored merchants
     * (the drill-down takes one; {@code get_merchant_totals} adds a group up) are errors.
     */
    public record Merchant() implements Type {

        @Override
        public void schema(ObjectNode property) {
            property.put("type", "string").put("minLength", 1).put("maxLength", MAX_TEXT);
        }

        @Override
        public Parsed parse(String name, JsonNode value, ToolScope scope) {
            if (!value.isString()) {
                return Parsed.problem(name, ToolProblem.NOT_TEXT);
            }
            String text = value.stringValue();
            if (text.length() > MAX_TEXT) {
                return Parsed.problem(name, ToolProblem.TEXT_TOO_LONG);
            }
            if (scope.isWithheldMerchant(text)) {
                return Parsed.problem(name, ToolProblem.WITHHELD_MERCHANT);
            }
            List<String> stored = scope.storedMerchants(text);
            return switch (stored.size()) {
                case 0 -> Parsed.problem(name, ToolProblem.NOT_A_MERCHANT);
                case 1 -> Parsed.ok(stored.getFirst());
                default -> Parsed.problem(name, ToolProblem.SEVERAL_MERCHANTS);
            };
        }
    }

    /**
     * 1 to {@value #MAX_MERCHANTS} merchants exactly as results show them, each once (docs/plans/phase-6.md §4.3); parsed
     * to a list of {@link MerchantGroup}s, each with every stored merchant its text stands for. The withheld placeholder
     * and a text no merchant goes out as are errors, reported on the list (an element's text is never echoed).
     */
    public record Merchants() implements Type {

        public static final int MAX_MERCHANTS = 10;

        @Override
        public void schema(ObjectNode property) {
            property.put("type", "array").put("minItems", 1).put("maxItems", MAX_MERCHANTS);
            property.putObject("items").put("type", "string").put("minLength", 1).put("maxLength", MAX_TEXT);
        }

        @Override
        public Parsed parse(String name, JsonNode value, ToolScope scope) {
            if (!value.isArray()) {
                return Parsed.problem(name, ToolProblem.NOT_A_LIST);
            }
            if (value.size() < 1 || value.size() > MAX_MERCHANTS) {
                return Parsed.problem(name, ToolProblem.OUT_OF_BOUNDS);
            }
            List<MerchantGroup> groups = new ArrayList<>();
            for (JsonNode element : value.values()) {
                if (!element.isString()) {
                    return Parsed.problem(name, ToolProblem.NOT_TEXT);
                }
                String text = element.stringValue();
                if (text.length() > MAX_TEXT) {
                    return Parsed.problem(name, ToolProblem.TEXT_TOO_LONG);
                }
                if (scope.isWithheldMerchant(text)) {
                    return Parsed.problem(name, ToolProblem.WITHHELD_MERCHANT);
                }
                if (groups.stream().anyMatch(g -> g.text().equals(text))) {
                    return Parsed.problem(name, ToolProblem.LISTED_TWICE);
                }
                List<String> stored = scope.storedMerchants(text);
                if (stored.isEmpty()) {
                    return Parsed.problem(name, ToolProblem.NOT_A_MERCHANT);
                }
                groups.add(new MerchantGroup(text, stored));
            }
            return Parsed.ok(List.copyOf(groups));
        }
    }

    /**
     * What the user is looking for, in their words, {@value #MIN_QUERY} to {@value #MAX_QUERY} characters
     * (docs/plans/phase-6.md §4.3); parsed to the {@link SearchText} the scope prepared: redacted, checked and embedded.
     * A text that may not leave the machine is an error, and nothing of it is sent.
     */
    public record Search() implements Type {

        public static final int MIN_QUERY = 2;
        public static final int MAX_QUERY = 60;

        @Override
        public void schema(ObjectNode property) {
            property.put("type", "string").put("minLength", MIN_QUERY).put("maxLength", MAX_QUERY);
        }

        @Override
        public Parsed parse(String name, JsonNode value, ToolScope scope) {
            if (!value.isString()) {
                return Parsed.problem(name, ToolProblem.NOT_TEXT);
            }
            String text = value.stringValue();
            if (text.strip().length() < MIN_QUERY) {
                return Parsed.problem(name, ToolProblem.TEXT_TOO_SHORT);
            }
            if (text.length() > MAX_QUERY) {
                return Parsed.problem(name, ToolProblem.TEXT_TOO_LONG);
            }
            return scope.searchText(text).map(Parsed::ok)
                    .orElseGet(() -> Parsed.problem(name, ToolProblem.SEARCH_REFUSED));
        }
    }

    /**
     * A period: {@code {"month": "2026-08"}} or {@code {"from": "2026-06", "to": "2026-08"}}, at most
     * {@value #MAX_MONTHS} months; parsed to a {@link MonthRange}. Problems are named {@code a.month}, {@code a.from}.
     */
    public record Period() implements Type {

        private static final List<String> KEYS = List.of("month", "from", "to");

        @Override
        public void schema(ObjectNode property) {
            property.put("type", "object").put("additionalProperties", false);
            ObjectNode properties = property.putObject("properties");
            KEYS.forEach(key -> properties.putObject(key).put("type", "string").put("pattern", MONTH.pattern()));
        }

        @Override
        public Parsed parse(String name, JsonNode value, ToolScope scope) {
            if (!value.isObject() || !KEYS.containsAll(value.propertyNames())) {
                return Parsed.problem(name, ToolProblem.NOT_A_PERIOD);
            }
            JsonNode month = present(value.get("month"));
            JsonNode from = present(value.get("from"));
            JsonNode to = present(value.get("to"));
            if (month != null && (from != null || to != null) || month == null && (from == null || to == null)) {
                return Parsed.problem(name, ToolProblem.NOT_A_PERIOD);
            }
            if (month != null) {
                return month(month).map(m -> Parsed.ok(new MonthRange(m, m)))
                        .orElseGet(() -> Parsed.problem(name + ".month", ToolProblem.NOT_A_MONTH));
            }
            Optional<YearMonth> start = month(from);
            if (start.isEmpty()) {
                return Parsed.problem(name + ".from", ToolProblem.NOT_A_MONTH);
            }
            Optional<YearMonth> end = month(to);
            if (end.isEmpty()) {
                return Parsed.problem(name + ".to", ToolProblem.NOT_A_MONTH);
            }
            if (start.get().isAfter(end.get())) {
                return Parsed.problem(name + ".from", ToolProblem.REVERSED_RANGE);
            }
            MonthRange range = new MonthRange(start.get(), end.get());
            return range.length() > MAX_MONTHS ? Parsed.problem(name, ToolProblem.RANGE_TOO_LONG) : Parsed.ok(range);
        }
    }

    /** Null counts as left out. */
    static JsonNode present(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    private static Optional<YearMonth> month(JsonNode value) {
        if (!value.isString() || !MONTH.matcher(value.stringValue()).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(YearMonth.parse(value.stringValue()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
