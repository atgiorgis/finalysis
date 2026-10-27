package com.finalysis.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The recovery key's text (docs/plans/phase-8.md §4.2): 52 Crockford base32 characters in 13 groups of four. */
class RecoveryKeyTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Test
    void formatsAs13GroupsOfFourAndParsesBack() {
        for (int i = 0; i < 200; i++) {
            try (RecoveryKey key = RecoveryKey.generate(RANDOM)) {
                String text = key.formatted();
                assertThat(text).matches("([0-9A-HJKMNP-TV-Z]{4}-){12}[0-9A-HJKMNP-TV-Z]{4}");
                try (RecoveryKey parsed = RecoveryKey.parse(text).orElseThrow()) {
                    assertThat(parsed.bytes()).isEqualTo(key.bytes());
                }
            }
        }
    }

    @Test
    void knownBytesHaveAKnownText() {
        byte[] zeros = new byte[32];
        try (RecoveryKey key = RecoveryKey.of(zeros)) {
            assertThat(key.formatted()).isEqualTo("0000-".repeat(12) + "0000");
        }
        byte[] ones = new byte[32];
        Arrays.fill(ones, (byte) 0xff);
        try (RecoveryKey key = RecoveryKey.of(ones)) {
            // 256 one-bits: 51 characters of 31 (Z), then one bit and four padding zeros: 10000 = 16 (G).
            assertThat(key.formatted().replace("-", "")).isEqualTo("Z".repeat(51) + "G");
        }
    }

    @Test
    void readsTypedKeysLeniently() {
        try (RecoveryKey key = RecoveryKey.generate(RANDOM)) {
            String text = key.formatted();
            String typed = " " + text.toLowerCase().replace("-", "  ").replace('0', 'o').replace('1', 'i') + "\n";
            try (RecoveryKey parsed = RecoveryKey.parse(typed).orElseThrow()) {
                assertThat(parsed.bytes()).isEqualTo(key.bytes());
            }
            String withL = text.replace('1', 'L');
            try (RecoveryKey parsed = RecoveryKey.parse(withL).orElseThrow()) {
                assertThat(parsed.bytes()).isEqualTo(key.bytes());
            }
        }
    }

    @Test
    void refusesWhatIsNotAKey() {
        String good;
        try (RecoveryKey key = RecoveryKey.generate(RANDOM)) {
            good = key.formatted();
        }
        assertThat(RecoveryKey.parse(null)).isEmpty();
        assertThat(RecoveryKey.parse("")).isEmpty();
        assertThat(RecoveryKey.parse(good.substring(0, good.length() - 1))).isEmpty();
        assertThat(RecoveryKey.parse(good + "0")).isEmpty();
        assertThat(RecoveryKey.parse(good.replaceFirst(".", "U"))).isEmpty(); // U is not in Crockford's alphabet
        assertThat(RecoveryKey.parse(good.replaceFirst(".", "#"))).isEmpty();
        // The last character carries one bit and four zero bits: a non-zero padding is not a key we made.
        String lastNotPadded = good.substring(0, good.length() - 1) + "1";
        assertThat(RecoveryKey.parse(lastNotPadded)).isEmpty();
    }

    @Test
    void closeZeroesTheBytesAndToStringHidesThem() {
        RecoveryKey key = RecoveryKey.generate(RANDOM);
        byte[] bytes = key.bytes();
        assertThat(key.toString()).doesNotContain(key.formatted().substring(0, 4) + "-");
        key.close();
        assertThat(bytes).containsOnly(0);
    }
}
