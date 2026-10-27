package com.finalysis.chat;

import com.finalysis.vault.VaultLocking;
import com.finalysis.vault.VaultUnlocked;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Conversation retention (docs/plans/phase-6.md §7.4, §18 decision 8): a conversation idle for
 * {@code finalysis.chat.retention-days} (default 90) is deleted with its turns, tool calls and summary, each time the
 * database opens (unlock, or startup with the vault off; docs/plans/phase-8.md §5.1) and every night while it is open; ledger rows older than that go too. Runs whether AI is on or off: it only deletes. Deleting one
 * conversation on demand is the API's ({@code DELETE /api/chat/conversations/{id}}), never a tool's. One count-only
 * log line.
 */
@Component
public class ChatRetention {

    private static final Logger log = LoggerFactory.getLogger(ChatRetention.class);

    private final ChatConversationStore store;
    private final ChatProperties properties;
    private final Clock clock;
    private volatile boolean open;

    public ChatRetention(ChatConversationStore store, ChatProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @EventListener(VaultUnlocked.class)
    public void atStartup() {
        open = true;
        purge();
    }

    @EventListener(VaultLocking.class)
    public void onLocking() {
        open = false;
    }

    /** Skipped while the vault is locked: the next unlock purges. */
    @Scheduled(cron = "0 30 3 * * *")
    public void nightly() {
        if (open) {
            purge();
        }
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
