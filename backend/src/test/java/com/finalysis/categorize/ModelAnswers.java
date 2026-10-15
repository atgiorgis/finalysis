package com.finalysis.categorize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds a fake model's answers from the prompt it was sent, as a model would: one answer per item, by key. Lets tests
 * answer by merchant without knowing which key the batch gave it, and corrupt answers on purpose.
 */
final class ModelAnswers {

    static final JsonMapper JSON = JsonMapper.builder().build();

    /** One item as sent. */
    record Sent(String key, String merchant, String amount) {
    }

    private ModelAnswers() {
    }

    /** The user message's transactions JSON, between its tags. */
    static JsonNode request(Prompt prompt) {
        return block(prompt.getInstructions().get(1).getText(), CategorizationPromptBuilder.OPEN_TAG,
                CategorizationPromptBuilder.CLOSE_TAG);
    }

    /** The user message's examples JSON, between its tags. */
    static JsonNode examples(Prompt prompt) {
        return block(prompt.getInstructions().get(1).getText(), CategorizationPromptBuilder.EXAMPLES_OPEN_TAG,
                CategorizationPromptBuilder.EXAMPLES_CLOSE_TAG);
    }

    /** The JSON between the first {@code open} and the last {@code close}. */
    static JsonNode block(String user, String open, String close) {
        return JSON.readTree(user.substring(user.indexOf(open) + open.length(), user.lastIndexOf(close)));
    }

    /** Every example sent, as {@code merchant=category}. */
    static List<String> exampleList(Prompt prompt) {
        List<String> examples = new ArrayList<>();
        examples(prompt).get("examples").forEach(example -> examples.add(
                example.get("merchant").asString() + "=" + example.get("category").asString()));
        return examples;
    }

    static List<Sent> items(Prompt prompt) {
        List<Sent> items = new ArrayList<>();
        request(prompt).get("items").forEach(item -> items.add(
                new Sent(item.get("key").asString(), item.get("merchant").asString(), item.get("amount").asString())));
        return items;
    }

    /** Every item answered with the category {@code categoryOf} gives it, at this confidence. */
    static Function<Prompt, String> answering(Function<Sent, String> categoryOf, double confidence) {
        return prompt -> json(items(prompt).stream()
                .map(item -> answer(item.key(), categoryOf.apply(item), confidence))
                .toList());
    }

    /** Like {@link #answering}, then {@code corrupt} edits the list of answers before it is written. */
    static Function<Prompt, String> answering(Function<Sent, String> categoryOf, double confidence,
                                              Consumer<List<Map<String, Object>>> corrupt) {
        return prompt -> {
            List<Map<String, Object>> answers = new ArrayList<>(items(prompt).stream()
                    .map(item -> answer(item.key(), categoryOf.apply(item), confidence))
                    .toList());
            corrupt.accept(answers);
            return json(answers);
        };
    }

    static Map<String, Object> answer(String key, String category, Object confidence) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("key", key);
        answer.put("category", category);
        answer.put("confidence", confidence);
        return answer;
    }

    static String json(List<Map<String, Object>> answers) {
        return JSON.writeValueAsString(Map.of("answers", answers));
    }
}
