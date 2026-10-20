package com.finalysis.alert;

import java.time.YearMonth;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alerts (docs/plans/phase-5.md §5.2). The GETs map onto {@link AlertQueryService}; the dismissal pair, the only
 * writes, onto {@link AlertDismissalService}: a user action from the API (Postman now, the Phase 7 UI later), never a
 * chatbot tool.
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    /** The dismissal body; both it and its note are optional. */
    public record DismissalRequest(String note) {
    }

    private final AlertQueryService alerts;
    private final AlertDismissalService dismissals;
    private final AlertProperties properties;

    public AlertController(AlertQueryService alerts, AlertDismissalService dismissals, AlertProperties properties) {
        this.alerts = alerts;
        this.dismissals = dismissals;
        this.properties = properties;
    }

    /** {@code limit} defaults to {@code finalysis.alerts.max-results}. */
    @GetMapping
    public AlertListResponse alerts(@RequestParam(required = false) YearMonth from,
                                    @RequestParam(required = false) YearMonth to,
                                    @RequestParam(required = false) AlertType type,
                                    @RequestParam(required = false) Long accountId,
                                    @RequestParam(defaultValue = "false") boolean includeDismissed,
                                    @RequestParam(required = false) Integer limit) {
        return alerts.alerts(Optional.ofNullable(from), Optional.ofNullable(to), Optional.ofNullable(type),
                Optional.ofNullable(accountId), includeDismissed, limit == null ? properties.maxResults() : limit);
    }

    @GetMapping("/{key}")
    public Alert alert(@PathVariable String key) {
        return alerts.alert(key);
    }

    @PutMapping("/{key}/dismissal")
    public Alert dismiss(@PathVariable String key, @RequestBody(required = false) DismissalRequest body) {
        return dismissals.dismiss(key, body == null ? null : body.note());
    }

    @DeleteMapping("/{key}/dismissal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void undismiss(@PathVariable String key) {
        dismissals.undismiss(key);
    }
}
