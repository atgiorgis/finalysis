package com.finalysis.chat.outbound;

import com.finalysis.ai.outbound.OutboundPayloadGuard;
import com.finalysis.chat.tool.ToolProblem;
import com.finalysis.chat.tool.view.AccountTotalsView;
import com.finalysis.chat.tool.view.AlertsView;
import com.finalysis.chat.tool.view.CategoryTotalsView;
import com.finalysis.chat.tool.view.ComparisonView;
import com.finalysis.chat.tool.view.ContextBlockView;
import com.finalysis.chat.tool.view.CoverageView;
import com.finalysis.chat.tool.view.DataNotesView;
import com.finalysis.chat.tool.view.MonthTotalsView;
import com.finalysis.chat.tool.view.Outbound;
import com.finalysis.chat.tool.view.OutboundKind;
import com.finalysis.chat.tool.view.RecurringView;
import com.finalysis.chat.tool.view.TopMerchantsView;
import com.finalysis.chat.tool.view.TransactionsView;
import com.finalysis.chat.tool.view.TrendsView;
import com.finalysis.chat.tool.view.ToolErrorView;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The last check on every chat payload (docs/plans/phase-6.md §12.3), over {@link OutboundPayloadGuard}: it walks a
 * view record and checks each field against its {@link Outbound} kind. It never repairs: a list element with a failing
 * field is dropped (counted), and a failing field outside any list refuses the whole payload. What it returns is a copy
 * of the view without the dropped rows; that copy is what is serialized and sent.
 *
 * <p>Every component of a view must carry a kind or be a view record (or a list of either); anything else fails
 * {@link #classify}, which runs on first use of a type and in a test over every view, so a new field can't slip out
 * unclassified. Checks per kind: money, percent, ratio, number, month and date by pattern; a category by the guard's
 * category rule; a merchant by the guard's merchant rule and {@link InstructionLikeText} (or the withheld placeholder);
 * an alias or ref only if this turn issued it; an enum constant's name; a tool parameter's name by pattern; a tool error's message only if it is one of
 * {@link ToolProblem}'s fixed texts; free text 1–1,000 characters without
 * {@code < > { }}.
 */
@Component
public class ChatPayloadGuard {

    private static final Pattern MONEY = Pattern.compile("-?\\d{1,10}\\.\\d{2}");
    private static final Pattern PERCENT = Pattern.compile("-?\\d{1,7}\\.\\d");
    private static final Pattern RATIO = Pattern.compile("\\d{1,7}\\.\\d{2}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,10}(?:\\.\\d{1,2})?");
    private static final Pattern MONTH = Pattern.compile("\\d{4}-(?:0[1-9]|1[0-2])");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern ENUM = Pattern.compile("[A-Z][A-Z0-9_]{0,40}");
    private static final Pattern REF = Pattern.compile("[tcas][1-9]\\d{0,3}");
    private static final Pattern SETTING = Pattern.compile("[a-z][a-z0-9.-]{0,60}");
    private static final Pattern PARAM = Pattern.compile("[a-z][A-Za-z]{0,30}(?:\\.[a-z][A-Za-z]{0,30})?|\\(unknown\\)");
    private static final Pattern TEXT_FORBIDDEN = Pattern.compile("[<>{}]");
    static final int MAX_TEXT = 1000;

    private final OutboundPayloadGuard guard;
    private final Map<Class<?>, List<Field>> layouts = new ConcurrentHashMap<>();

    /** Every view that can be sent; classified at startup, so an unclassified field fails the app, not a turn. */
    public static final List<Class<? extends Record>> VIEWS = List.of(MonthTotalsView.class, CategoryTotalsView.class,
            AccountTotalsView.class, TopMerchantsView.class, TrendsView.class, ComparisonView.class,
            TransactionsView.class, RecurringView.class, AlertsView.class, CoverageView.class, ContextBlockView.class,
            DataNotesView.class, ToolErrorView.class);

    public ChatPayloadGuard(OutboundPayloadGuard guard) {
        this.guard = guard;
        VIEWS.forEach(this::classify);
    }

    /** A checked payload: the view without its dropped rows, and how many were dropped. */
    public record Checked<T>(T view, int rowsKept, int rowsDropped) {
    }

    /** One component and how to check it. */
    public record Field(RecordComponent component, OutboundKind kind, Class<?> nestedRecord, boolean list) {
    }

    /**
     * Checks a view for this turn.
     *
     * @throws PayloadRefusedException if a field outside any list fails; nothing of it may be sent
     */
    public <T extends Record> Checked<T> check(T view, OutboundTurn turn) {
        Counter counter = new Counter();
        T checked = filter(view, turn, counter);
        if (checked == null) {
            throw new PayloadRefusedException(view.getClass().getSimpleName());
        }
        return new Checked<>(checked, counter.kept, counter.dropped);
    }

    /** Whether a free text (the outbound question, a shown answer, the summary) may be sent. */
    public boolean allowsText(String text) {
        return text != null && !text.isBlank() && text.length() <= MAX_TEXT && !TEXT_FORBIDDEN.matcher(text).find();
    }

    /**
     * The layout of a view type: every component's kind, or the view record it contains.
     *
     * @throws IllegalStateException for a component with no kind that is not a view record, or a kind on a type it
     *                               can't hold
     */
    public List<Field> classify(Class<?> type) {
        return layouts.computeIfAbsent(type, ChatPayloadGuard::layout);
    }

    private static List<Field> layout(Class<?> type) {
        if (!type.isRecord()) {
            throw new IllegalStateException(type.getName() + " is not a view record");
        }
        List<Field> fields = new ArrayList<>();
        for (RecordComponent c : type.getRecordComponents()) {
            Outbound outbound = c.getAnnotation(Outbound.class);
            boolean list = List.class.isAssignableFrom(c.getType());
            Class<?> element = list ? elementType(c) : c.getType();
            if (outbound != null) {
                checkHolds(type, c, outbound.value(), element);
                fields.add(new Field(c, outbound.value(), null, list));
            } else if (element.isRecord()) {
                layout(element); // fails now for an unclassified nested component
                fields.add(new Field(c, null, element, list));
            } else {
                throw new IllegalStateException(type.getName() + "." + c.getName()
                        + " has no @Outbound kind and is not a view record: it could leave unchecked");
            }
        }
        return List.copyOf(fields);
    }

    private static void checkHolds(Class<?> type, RecordComponent c, OutboundKind kind, Class<?> element) {
        Class<?> expected = switch (kind) {
            case COUNT -> Integer.class;
            case FLAG -> Boolean.class;
            default -> String.class;
        };
        if (element != expected) {
            throw new IllegalStateException(type.getName() + "." + c.getName() + " is " + kind + " but holds "
                    + element.getSimpleName() + ", not " + expected.getSimpleName());
        }
    }

    private static Class<?> elementType(RecordComponent c) {
        Type generic = c.getGenericType();
        if (generic instanceof ParameterizedType p && p.getActualTypeArguments()[0] instanceof Class<?> element) {
            return element;
        }
        throw new IllegalStateException(c.getDeclaringRecord().getName() + "." + c.getName() + " is a raw list");
    }

    /** The record without its failing rows, or null when one of its own fields fails. */
    @SuppressWarnings("unchecked")
    private <T> T filter(T record, OutboundTurn turn, Counter counter) {
        List<Field> fields = classify(record.getClass());
        Object[] values = new Object[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            Object value = read(field.component(), record);
            if (value == null) {
                values[i] = null;
            } else if (field.kind() != null && !field.list()) {
                if (!allows(field.kind(), value, turn)) {
                    return null;
                }
                values[i] = value;
            } else if (field.kind() != null) {
                List<Object> kept = new ArrayList<>();
                for (Object element : (List<Object>) value) {
                    if (element != null && allows(field.kind(), element, turn)) {
                        kept.add(element);
                    } else {
                        counter.dropped++;
                    }
                }
                values[i] = List.copyOf(kept);
            } else if (!field.list()) {
                Object nested = filter(value, turn, counter);
                if (nested == null) {
                    return null;
                }
                values[i] = nested;
            } else {
                List<Object> kept = new ArrayList<>();
                for (Object element : (List<Object>) value) {
                    Object nested = element == null ? null : filter(element, turn, counter);
                    if (nested == null) {
                        counter.dropped++;
                    } else {
                        kept.add(nested);
                        counter.kept++;
                    }
                }
                values[i] = List.copyOf(kept);
            }
        }
        return (T) construct(record.getClass(), fields, values);
    }

    private boolean allows(OutboundKind kind, Object value, OutboundTurn turn) {
        return switch (kind) {
            case COUNT, FLAG -> true; // the type is the check (classify)
            case MONEY -> MONEY.matcher((String) value).matches();
            case PERCENT -> PERCENT.matcher((String) value).matches();
            case RATIO -> RATIO.matcher((String) value).matches();
            case NUMBER -> NUMBER.matcher((String) value).matches();
            case MONTH -> MONTH.matcher((String) value).matches();
            case DATE -> isDate((String) value);
            case CATEGORY -> guard.allowsCategory((String) value);
            case MERCHANT -> turn.merchantMayGo((String) value);
            case ALIAS -> turn.aliases().isAlias((String) value);
            case ENUM -> ENUM.matcher((String) value).matches();
            case REF -> REF.matcher((String) value).matches() && turn.refs().issued((String) value);
            case SETTING -> SETTING.matcher((String) value).matches();
            case PARAM -> PARAM.matcher((String) value).matches();
            case MESSAGE -> ToolProblem.isText((String) value);
            case TEXT -> allowsText((String) value);
        };
    }

    private static boolean isDate(String value) {
        if (!DATE.matcher(value).matches()) {
            return false;
        }
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static Object read(RecordComponent component, Object record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("can't read " + component.getName(), e);
        }
    }

    private static Object construct(Class<?> type, List<Field> fields, Object[] values) {
        try {
            Class<?>[] types = fields.stream().map(f -> f.component().getType()).toArray(Class<?>[]::new);
            Constructor<?> canonical = type.getDeclaredConstructor(types);
            return canonical.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't rebuild " + type.getSimpleName(), e);
        }
    }

    private static final class Counter {
        int kept;
        int dropped;
    }
}
