package com.finalysis.vault;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code db-check.sh}'s line read by the backend (docs/plans/phase-8.md §4.4): each NOT_READY reason becomes one plain
 * sentence; the version messages name the installed and the required version; nothing that looks like a path gets
 * through.
 */
class ReadyCheckTest {

    @Test
    void ok() {
        assertThat(ReadyCheck.parse("OK\n").ok()).isTrue();
    }

    @Test
    void versionMessagesNameBothVersions() {
        assertThat(ReadyCheck.parse("NOT_READY postgres-wrong-version installed=16.4 required=17").message())
                .contains("PostgreSQL 17", "PostgreSQL 16.4");
        assertThat(ReadyCheck.parse("NOT_READY pgvector-wrong-version installed=0.8.7 required=0.8.6").message())
                .contains("pgvector 0.8.6", "0.8.7");
        assertThat(ReadyCheck.parse("NOT_READY pgvector-missing required=0.8.6").message()).contains("0.8.6");
        assertThat(ReadyCheck.parse("NOT_READY postgres-missing required=17").message())
                .contains("Postgres.app", "finalysis.vault.pg-bin");
        assertThat(ReadyCheck.parse("NOT_READY port-in-use item=55432").message()).contains("55432");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres-missing", "postgres-wrong-version", "pgvector-missing", "pgvector-wrong-version",
            "launchd-conflict", "postgresapp-conflict", "port-in-use", "something-new"})
    void everyReasonHasASentence(String reason) {
        ReadyCheck check = ReadyCheck.parse("NOT_READY " + reason);
        assertThat(check.ok()).isFalse();
        assertThat(check.reason()).isEqualTo(reason);
        assertThat(check.message()).endsWith(".").doesNotContain("null");
    }

    @Test
    void pathsAndGarbageDontGetThrough() {
        ReadyCheck check = ReadyCheck.parse("NOT_READY launchd-conflict item=/Users/someone/Library/x.plist");
        assertThat(check.item()).isNull();
        assertThat(check.message()).doesNotContain("/Users");
        assertThat(ReadyCheck.parse("something else entirely").reason()).isEqualTo("check-failed");
        assertThat(ReadyCheck.parse(null).ok()).isFalse();
    }
}
