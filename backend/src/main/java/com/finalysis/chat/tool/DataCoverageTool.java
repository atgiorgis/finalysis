package com.finalysis.chat.tool;

import com.finalysis.analytics.CoverageService;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** {@code get_data_coverage} over {@code CoverageService.coverage} (docs/plans/phase-6.md §3.2). */
@Component
class DataCoverageTool implements ChatTool {

    private final CoverageService coverage;

    DataCoverageTool(CoverageService coverage) {
        this.coverage = coverage;
    }

    @Override
    public ToolName name() {
        return ToolName.GET_DATA_COVERAGE;
    }

    @Override
    public String description() {
        return "Which days each account's statements cover: covered spans and gaps (dates), flagged statements "
                + "(failed or unverified balance checks) and the latest month and latest complete month with data. Use it "
                + "to tell the user what data exists before answering about a month.";
    }

    @Override
    public List<ToolParam> params() {
        return List.of();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolRead read(ToolArguments arguments) {
        return new ToolRead.Coverage(coverage.coverage());
    }
}
