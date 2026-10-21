package com.finalysis.chat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Conversation retention (docs/plans/phase-6.md §7.4, §18 decision 8): a conversation idle for
 * {@code finalysis.chat.retention-days} (default 90) is deleted with its turns, tool calls and summary, at startup and
 * every night; ledger rows older than that go too. Runs whether AI is on or off: it only deletes. Deleting one
 * conversation on demand is the API's ({@code DELETE /api/chat/conversations/{id}}), never a tool's. One count-only
 * log line.
 */
@Component
public class ChatRetention {

    private static final Logger log = LoggerFactory.getLogger(ChatRetention.class);

    private final ChatConversationStore store;
    private final ChatProperties properties;
    private final Clock clock;

    public ChatRetention(ChatConversationStore store, ChatProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStartup() {
        purge();
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void nightly() {
        purge();
    }

    /** Deletes what has been idle longer than the retention; returns what went. */
    public ChatConversationStore.Purged purge() {
        OffsetDateTime idleSince = OffsetDateTime.now(clock).minusDays(properties.retentionDays())
                .truncatedTo(ChronoUnit.MICROS);
        ChatConversationStore.Purged purged = store.purgeIdle(idleSince);
        log.info("chat retention days={} conversations={} usageRows={}", properties.retentionDays(),
                purged.conversations(), purged.usageRows());
        return purged;
    }
}
