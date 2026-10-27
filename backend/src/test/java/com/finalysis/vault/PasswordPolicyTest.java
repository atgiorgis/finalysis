package com.finalysis.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The vault password rule (docs/plans/phase-8.md §4.5, §16): 12 characters and none of the 1,000 most common. */
class PasswordPolicyTest {

    @Test
    void needsTwelveCharactersCountedAsCharactersNotBytes() {
        PasswordPolicy policy = new PasswordPolicy(Set.of());
        assertThatThrownBy(() -> policy.check(null)).isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> policy.check("elevenchars")).hasMessageContaining("12 characters");
        assertThatCode(() -> policy.check("twelve chars")).doesNotThrowAnyException();
        // Six emoji are 12 UTF-16 units but six characters.
        assertThatThrownBy(() -> policy.check("😀😀😀😀😀😀")).hasMessageContaining("12 characters");
        assertThatCode(() -> policy.check("ééééééééééé1")).doesNotThrowAnyException();
    }

    @Test
    void refusesACommonPasswordIgnoringCase() {
        PasswordPolicy policy = new PasswordPolicy(Set.of("correcthorsebattery"));
        assertThatThrownBy(() -> policy.check("CorrectHorseBattery")).isInstanceOf(VaultException.class)
                .hasMessageContaining("most common");
        assertThatCode(() -> policy.check("correcthorsebatterystaple")).doesNotThrowAnyException();
    }

    @Test
    void theRejectionNeverQuotesThePassword() {
        PasswordPolicy policy = new PasswordPolicy(Set.of("a long common password"));
        assertThatThrownBy(() -> policy.check("a long common password")).message()
                .doesNotContain("a long common password");
        assertThatThrownBy(() -> policy.check("short pw")).message().doesNotContain("short pw");
    }

    @Test
    void theBundledListIsTheRecordedOneThousand() throws Exception {
        // 1,000 lines; 997 distinct once case is ignored ("Password" and "password" are one).
        assertThat(new PasswordPolicy().size()).isEqualTo(997);
        try (InputStream in = PasswordPolicy.class.getResourceAsStream("/vault/common-passwords.txt")) {
            assertThat(new String(in.readAllBytes()).lines().count()).isEqualTo(1000);
        }
        try (InputStream in = PasswordPolicy.class.getResourceAsStream("/vault/common-passwords.txt")) {
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
            assertThat(sha).isEqualTo("6a23e801cac67769788726912d187ae569a681dc1da57f8dac1bcadcf546f33e");
        }
        try (InputStream notice = PasswordPolicy.class.getResourceAsStream("/vault/common-passwords.NOTICE")) {
            assertThat(new String(notice.readAllBytes())).contains("MIT License", "SecLists",
                    "6a23e801cac67769788726912d187ae569a681dc1da57f8dac1bcadcf546f33e");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        // Recorded in the step 2 notes: no entry of this list has 12 characters, so with the 12-character minimum the
        // list check can't refuse anything today; it only matters if the minimum drops or a longer list replaces it.
        try (InputStream in = PasswordPolicy.class.getResourceAsStream("/vault/common-passwords.txt")) {
            assertThat(new String(in.readAllBytes()).lines().mapToInt(String::length).max().orElse(0))
                    .isLessThan(PasswordPolicy.MIN_LENGTH);
        }
    }
}
