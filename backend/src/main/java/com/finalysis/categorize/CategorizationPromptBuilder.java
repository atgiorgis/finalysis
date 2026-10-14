package com.finalysis.categorize;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the one thing a categorization call may send (docs/plans/phase-4.md §4.2, §4.3, §5): the fixed system
 * prompt from {@code categorize/categorization-system-prompt.txt}, and a user message that is the
 * {@link CategorizationRequest} as JSON between {@code <transactions>} and {@code </transactions>}.
 *
 * <p>For each candidate, in order: a person-to-person payment is skipped; the merchant is redacted and must pass
 * the guard, or the candidate is skipped ({@code redactionBlocked}); its amount must pass too. Each example's
 * merchant gets exactly the same redaction and check, and its category must be an allowed one, or the example
 * is dropped; at most three are kept. Keys ({@code i1}…) are given to the sent items only, and the mapping back
 * to candidates stays here, in Java.
 *
 * <p>JSON comes from Jackson, so every string is escaped; angle brackets are also written as JSON unicode
 * escapes (a backslash, then {@code u003c} or {@code u003e}), so no string can close the tag even if it got past
 * the guard. Pure Java: no model client.
 */
@Component
public class CategorizationPromptBuilder {

    public static final String SYSTEM_PROMPT_RESOURCE = "categorize/categorization-system-prompt.txt";
    public static final String OPEN_TAG = "<transactions>";
    public static final String CLOSE_TAG = "</transactions>";
    static final int MAX_EXAMPLES = 3;

    private final Redactor redactor;
    private final OutboundPayloadGuard guard;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String systemPrompt = loadSystemPrompt();

    public CategorizationPromptBuilder(Redactor redactor, OutboundPayloadGuard guard) {
        this.redactor = redactor;
        this.guard = guard;
    }

    /**
     * One merchant and direction as the pipeline knows it, locally. Only what passes redaction and the guard
     * leaves.
     *
     * @param counterparty the person of a person-to-person payment, if any; it is never sent and makes the
     *                     candidate skipped
     * @param amount       the most recent txn's amount, as stored
     */
    public record Candidate(String merchant, String counterparty, BigDecimal amount, List<LocalExample> similar) {

        public Candidate {
            similar = List.copyOf(similar);
        }
    }

    /** A similar merchant with a trusted label, before redaction. */
    public record LocalExample(String merchant, String category) {
    }

    /** Why a candidate wasn't sent. */
    public enum Skip {
        /** A person-to-person payment (§5.2): never sent. */
        PERSON_TO_PERSON,
        /** Redaction left nothing, or the guard refused the merchant or amount (§5.3). */
        REDACTION_BLOCKED
    }

    /**
     * What to send and how to map the answers back.
     *
     * @param system           the fixed system prompt
     * @param user             {@code <transactions>} + the request as JSON + {@code </transactions>}
     * @param candidateByKey   each sent key to the index of its candidate
     * @param skipped          each skipped candidate's index and reason; nothing to send when every one is
     */
    public record CategorizationPrompt(String system, String user, CategorizationRequest request,
                                       Map<String, Integer> candidateByKey, Map<Integer, Skip> skipped) {

        public boolean isEmpty() {
            return request.items().isEmpty();
        }
    }

    /**
     * @param categories every category name; all must pass the guard, or nothing is built
     * @throws IllegalStateException if a category name can't be sent
     */
    public CategorizationPrompt build(List<String> categories, List<Candidate> candidates, RedactionContext context) {
        List<String> allowed = categories.stream().sorted().toList();
        if (!allowed.stream().allMatch(guard::allowsCategory)) {
            throw new IllegalStateException("A category name doesn't pass the outbound check; nothing is sent");
        }
        Set<String> allowedSet = Set.copyOf(allowed);

        List<CategorizationRequest.Item> items = new ArrayList<>();
        Map<String, Integer> candidateByKey = new LinkedHashMap<>();
        Map<Integer, Skip> skipped = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (redactor.isPersonToPerson(candidate.merchant(), candidate.counterparty())) {
                skipped.put(i, Skip.PERSON_TO_PERSON);
                continue;
            }
            String merchant = outboundMerchant(candidate.merchant(), context);
            String amount = amount(candidate.amount());
            if (merchant == null || !guard.allowsAmount(amount)) {
                skipped.put(i, Skip.REDACTION_BLOCKED);
                continue;
            }
            String key = "i" + (items.size() + 1);
            if (!guard.allowsKey(key)) {
                throw new IllegalStateException("Too many items for one request");
            }
            items.add(new CategorizationRequest.Item(key, merchant, amount, examples(candidate, allowedSet, context)));
            candidateByKey.put(key, i);
        }

        CategorizationRequest request = new CategorizationRequest(allowed, items);
        String body = json.writeValueAsString(request).replace("<", "\\u003c").replace(">", "\\u003e");
        return new CategorizationPrompt(systemPrompt, OPEN_TAG + body + CLOSE_TAG, request,
                Map.copyOf(candidateByKey), Map.copyOf(skipped));
    }

    /** The redacted merchant if the guard allows it, else null. The one path for items and examples alike. */
    private String outboundMerchant(String merchant, RedactionContext context) {
        String redacted = redactor.redact(merchant, context);
        return guard.allowsMerchant(redacted, context) ? redacted : null;
    }

    private List<CategorizationRequest.Example> examples(Candidate candidate, Set<String> allowed,
                                                         RedactionContext context) {
        List<CategorizationRequest.Example> examples = new ArrayList<>();
        for (LocalExample example : candidate.similar()) {
            if (examples.size() == MAX_EXAMPLES) {
                break;
            }
            if (!allowed.contains(example.category()) || redactor.isPersonToPerson(example.merchant(), null)) {
                continue;
            }
            String merchant = outboundMerchant(example.merchant(), context);
            if (merchant != null) {
                examples.add(new CategorizationRequest.Example(merchant, example.category()));
            }
        }
        return examples;
    }

    /** The stored amount as written in the request ({@code -25.67}); formatting only, no arithmetic. */
    private static String amount(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException e) {
            return null; // more than two decimals: not a stored amount, so not sent
        }
    }

    private static String loadSystemPrompt() {
        try (InputStream in = CategorizationPromptBuilder.class.getClassLoader().getResourceAsStream(SYSTEM_PROMPT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(SYSTEM_PROMPT_RESOURCE + " is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException(SYSTEM_PROMPT_RESOURCE + " could not be read", e);
        }
    }
}
