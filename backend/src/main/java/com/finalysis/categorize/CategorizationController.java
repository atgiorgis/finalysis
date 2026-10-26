package com.finalysis.categorize;

import com.finalysis.categorize.AiCategorizer.AiRunResult;
import com.finalysis.categorize.CategorizationService.RunResult;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Categorization rules and on-demand runs over the API (CAT-01, CAT-04; docs/plans/phase-4.md §9.2). User
 * actions only: chatbot tools never call these (SEC-03).
 */
@RestController
@RequestMapping("/api/categorization")
public class CategorizationController {

    private final RuleService rules;
    private final CategorizationService categorization;
    private final AiCategorizer aiCategorizer;
    private final MerchantIndexRefresh searchIndex;
    private final UncategorizedCount uncategorized;

    public CategorizationController(RuleService rules, CategorizationService categorization,
                                    AiCategorizer aiCategorizer, MerchantIndexRefresh searchIndex,
                                    UncategorizedCount uncategorized) {
        this.rules = rules;
        this.categorization = categorization;
        this.aiCategorizer = aiCategorizer;
        this.searchIndex = searchIndex;
        this.uncategorized = uncategorized;
    }

    /** In winning order (§3.2). */
    @GetMapping("/rules")
    public List<RuleResponse> list(@RequestParam(required = false) RuleSource source,
                                   @RequestParam(required = false) Boolean active,
                                   @RequestParam(required = false) Long categoryId) {
        return rules.list(source, active, categoryId);
    }

    @GetMapping("/rules/{id:\\d+}")
    public RuleResponse get(@PathVariable long id) {
        return rules.get(id);
    }

    @PostMapping("/rules")
    public ResponseEntity<RuleChangeResponse> create(@Valid @RequestBody CreateRuleRequest request) {
        RuleChangeResponse created = rules.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/categorization/rules/{id}").buildAndExpand(created.rule().id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PatchMapping("/rules/{id:\\d+}")
    public RuleChangeResponse update(@PathVariable long id, @Valid @RequestBody UpdateRuleRequest request) {
        return rules.update(id, request);
    }

    @DeleteMapping("/rules/{id:\\d+}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        rules.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Runs every stage now (§1.2): with {@code reclean}, re-cleans every merchant first; then rules and memory, in
     * one transaction; then, when AI is on and {@code ai} isn't false, the AI stage, with no transaction held across
     * its calls. {@code force} also asks again for txns the AI already categorized or left for review; USER and RULE
     * categories never change. After an AI run, the chat search index is refreshed (docs/plans/phase-6.md §4.2); its
     * counts are returned (step 7e) with a summary the UI says in words.
     */
    @PostMapping("/run")
    public RunResponse run(@RequestParam(defaultValue = "false") boolean reclean,
                           @RequestParam(defaultValue = "true") boolean ai,
                           @RequestParam(defaultValue = "false") boolean force) {
        RunResult rulesResult = categorization.run(reclean);
        AiRunResult aiResult = ai ? aiCategorizer.run(force) : AiRunResult.off();
        MerchantIndexRefresh.Refresh refreshed = aiResult.status() != AiCategorizer.Status.OFF
                ? searchIndex.refreshCounted()
                : new MerchantIndexRefresh.Refresh(MerchantEmbeddingStage.EmbeddingRunResult.off(), 0);
        return RunResponse.of(rulesResult, aiResult, refreshed, uncategorized.count());
    }
}
