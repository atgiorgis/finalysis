package com.finalysis.categorize;

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

    public CategorizationController(RuleService rules, CategorizationService categorization) {
        this.rules = rules;
        this.categorization = categorization;
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

    /** Runs every stage now (§1.2); with {@code reclean}, re-cleans every merchant first. */
    @PostMapping("/run")
    public RunResult run(@RequestParam(defaultValue = "false") boolean reclean) {
        return categorization.run(reclean);
    }
}
