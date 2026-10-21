package com.finalysis.chat.tool;

import com.finalysis.analytics.MonthRange;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * A tool call's arguments, checked against the tool's parameters (docs/plans/phase-6.md §3.1): every property known,
 * every required one present, every value of its type and resolved (aliases and categories to ids, a merchant to its
 * stored name). Built only by {@link #parse}, so a tool reads nothing the model sent unchecked.
 */
public final class ToolArguments {

    private final Map<String, Object> values;

    private ToolArguments(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    /** The arguments, or every problem with them; never both. */
    public record Parsed(ToolArguments arguments, List<FieldProblem> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /**
     * Checks the model's JSON against the parameters. Unknown properties are reported as {@link FieldProblem#UNKNOWN},
     * once, so their names are never echoed.
     */
    public static Parsed parse(JsonNode json, List<ToolParam> params, ToolScope scope) {
        List<FieldProblem> problems = new ArrayList<>();
        if (json == null || !json.isObject()) {
            return new Parsed(null, List.of(new FieldProblem(FieldProblem.UNKNOWN, ToolProblem.NOT_AN_OBJECT)));
        }
        List<String> known = params.stream().map(ToolParam::name).toList();
        if (!known.containsAll(json.propertyNames())) {
            problems.add(new FieldProblem(FieldProblem.UNKNOWN, ToolProblem.UNKNOWN_PARAMETER));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (ToolParam param : params) {
            JsonNode value = ToolParam.present(json.get(param.name()));
            if (value == null) {
                if (param.required()) {
                    problems.add(new FieldProblem(param.name(), ToolProblem.REQUIRED));
                }
                continue;
            }
            ToolParam.Parsed parsed = param.type().parse(param.name(), value, scope);
            if (parsed.problem() != null) {
                problems.add(parsed.problem());
            } else {
                values.put(param.name(), parsed.value());
            }
        }
        return problems.isEmpty() ? new Parsed(new ToolArguments(values), List.of())
                : new Parsed(null, List.copyOf(problems));
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public Optional<YearMonth> month(String name) {
        return Optional.ofNullable((YearMonth) values.get(name));
    }

    public Optional<Long> id(String name) {
        return Optional.ofNullable((Long) values.get(name));
    }

    public Optional<String> text(String name) {
        return Optional.ofNullable((String) values.get(name));
    }

    public <E extends Enum<E>> Optional<E> choice(String name, Class<E> type) {
        return Optional.ofNullable(values.get(name)).map(type::cast);
    }

    public int number(String name) {
        return (Integer) values.get(name);
    }

    public boolean flag(String name, boolean otherwise) {
        return (Boolean) values.getOrDefault(name, otherwise);
    }

    @SuppressWarnings("unchecked")
    public List<MerchantGroup> merchants(String name) {
        return (List<MerchantGroup>) values.get(name);
    }

    public SearchText search(String name) {
        return (SearchText) values.get(name);
    }

    public MonthRange period(String name) {
        return (MonthRange) values.get(name);
    }

    /**
     * The problems of an optional {@code from}–{@code to} pair given both ends: reversed, or longer than
     * {@value ToolParam#MAX_MONTHS} months. With one end only, the service applies its default and bound.
     */
    public List<FieldProblem> rangeProblems() {
        Optional<YearMonth> from = month("from");
        Optional<YearMonth> to = month("to");
        if (from.isEmpty() || to.isEmpty()) {
            return List.of();
        }
        if (from.get().isAfter(to.get())) {
            return List.of(new FieldProblem("from", ToolProblem.REVERSED_RANGE));
        }
        return new MonthRange(from.get(), to.get()).length() > ToolParam.MAX_MONTHS
                ? List.of(new FieldProblem("to", ToolProblem.RANGE_TOO_LONG)) : List.of();
    }

    /** A conflict between two given parameters, reported on the first. */
    public List<FieldProblem> conflict(String name, String with) {
        return has(name) && has(with) ? List.of(new FieldProblem(name, ToolProblem.CONFLICT, with)) : List.of();
    }
}
