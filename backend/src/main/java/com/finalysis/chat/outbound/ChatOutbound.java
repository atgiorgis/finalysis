package com.finalysis.chat.outbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends a projected view through {@link ChatPayloadGuard} and writes one count-only line per tool result
 * (docs/plans/phase-6.md §8, §18 decision 6):
 * <pre>chat outbound tool=list_transactions rows=13 droppedRows=0 withheldMerchants=4 refusedMerchants=1</pre>
 * The merchant counts are the distinct names this result newly withheld or refused (the turn's running totals, as
 * differences). Never a merchant, a figure or any other value.
 */
@Component
public class ChatOutbound {

    private static final Logger log = LoggerFactory.getLogger(ChatOutbound.class);

    private final ChatPayloadGuard guard;

    public ChatOutbound(ChatPayloadGuard guard) {
        this.guard = guard;
    }

    /**
     * @param withheldBefore the turn's {@link OutboundTurn#withheldMerchants()} before this view was projected
     * @param refusedBefore  the turn's {@link OutboundTurn#refusedMerchants()} before this view was projected
     */
    public <T extends Record> ChatPayloadGuard.Checked<T> send(String tool, T view, OutboundTurn turn,
                                                               int withheldBefore, int refusedBefore) {
        ChatPayloadGuard.Checked<T> checked = guard.check(view, turn);
        log.info("chat outbound tool={} rows={} droppedRows={} withheldMerchants={} refusedMerchants={}", tool,
                checked.rowsKept(), checked.rowsDropped(), turn.withheldMerchants() - withheldBefore,
                turn.refusedMerchants() - refusedBefore);
        return checked;
    }
}
