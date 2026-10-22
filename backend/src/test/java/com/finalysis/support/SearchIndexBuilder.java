package com.finalysis.support;

import com.finalysis.categorize.MerchantEmbeddingStage.EmbeddingRunResult;
import com.finalysis.categorize.MerchantIndexRefresh;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Builds the chat search index (V23 {@code search_embedding}) through the real refresh, with chosen merchants placed
 * near an anchor text by the {@link FakeEmbeddingModel}. Since step 8b a merchant's document is its outbound text plus
 * its category ({@code FIXTURE DINER (Dining)}), so a test names the merchant and this finds its document: one refresh
 * to learn the documents, then the placements, then the index built again from scratch.
 */
public final class SearchIndexBuilder {

    private record Placement(String merchant, String anchor, double similarity) {
    }

    private final JdbcTemplate jdbc;
    private final FakeEmbeddingModel model;
    private final MerchantIndexRefresh refresh;
    private final List<Placement> placements = new ArrayList<>();
    private final List<Placement> categories = new ArrayList<>();

    public SearchIndexBuilder(JdbcTemplate jdbc, FakeEmbeddingModel model, MerchantIndexRefresh refresh) {
        this.jdbc = jdbc;
        this.model = model;
        this.refresh = refresh;
    }

    /** Places the merchant's document at exactly {@code similarity} from {@code anchor}'s vector. */
    public SearchIndexBuilder near(String merchant, String anchor, double similarity) {
        placements.add(new Placement(merchant, anchor, similarity));
        return this;
    }

    /** Places a category's document (its name) at exactly {@code similarity} from {@code anchor}'s vector. */
    public SearchIndexBuilder categoryNear(String category, String anchor, double similarity) {
        categories.add(new Placement(category, anchor, similarity));
        return this;
    }

    /** Empties the index, resets the model, places the documents and refreshes; returns the refresh's counts. */
    public EmbeddingRunResult build() {
        jdbc.update("DELETE FROM search_embedding");
        model.reset();
        refresh.refresh();
        List<String> documents = List.copyOf(model.inputs());
        jdbc.update("DELETE FROM search_embedding");
        model.reset();
        for (Placement p : placements) {
            String document = documents.stream()
                    .filter(d -> d.equals(p.merchant()) || d.startsWith(p.merchant() + " ("))
                    .findFirst().orElseThrow(() -> new IllegalStateException(p.merchant() + " is not in the index"));
            model.near(document, p.anchor(), p.similarity());
        }
        categories.forEach(p -> model.near(p.merchant(), p.anchor(), p.similarity()));
        return refresh.refresh();
    }

    /** The document the index embedded for a merchant, from the last build's inputs. */
    public static String document(FakeEmbeddingModel model, String merchant) {
        return model.inputs().stream().filter(d -> d.equals(merchant) || d.startsWith(merchant + " ("))
                .findFirst().orElseThrow();
    }
}
