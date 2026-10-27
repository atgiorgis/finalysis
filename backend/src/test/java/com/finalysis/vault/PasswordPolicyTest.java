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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    // ---- rule (a): a common password repeated or padded (step 2 review) ------------------------------------------

    private static final PasswordPolicy REAL = new PasswordPolicy();

    @ParameterizedTest
    @ValueSource(strings = {
            "dragondragon", "DragonDragon", "monkeymonkeymonkey", "passwordpassword",
            "password1234", "!!!password!!!", "2024monkey!!", "Monkey12345!", "    football    ",
            "qwertyqwerty", "abc123abc123", "1qaz2wsx1qaz2wsx", "1qaz2wsx!!!!",
            "123456123456", "1234567890123", "111111111111", "!!1990!!1990!!", "000000000000", "#$%^&*1234&*",
            "dragon!dragon", "superman2024!"})
    void refusesACommonPasswordRepeatedOrPaddedWithDigitsOrSymbols(String password) {
        if (password.equals("dragon!dragon")) {
            // A symbol between the repeats isn't padding: dragon!dragon is not dragon repeated. Accepted.
            assertThatCode(() -> REAL.check(password)).doesNotThrowAnyException();
            return;
        }
        assertThatThrownBy(() -> REAL.check(password)).as(password).isInstanceOf(VaultException.class)
                .hasMessageContaining("most common").message().doesNotContain(password.strip());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "correct horse battery staple", "dragon fruit salad", "my dragon is 42!", "monkeyXmonkeyX12",
            "Tr0ub4dor&3 plus more", "9 lazy otters swim", "x7#kQ2!mZ9@p", "passwordX1234"})
    void acceptsLongPasswordsThatMerelyContainACommonWord(String password) {
        assertThatCode(() -> REAL.check(password)).as(password).doesNotThrowAnyException();
    }

    @Test
    void refusesOverlongPasswords() {
        assertThatThrownBy(() -> REAL.check("a7#".repeat(90))).hasMessageContaining("at most 256");
    }

    @Test
    void stays_fast_on_a_worst_case() {
        String worst = "1".repeat(127) + "a" + "1".repeat(127);
        long start = System.nanoTime();
        assertThatCode(() -> REAL.check(worst)).doesNotThrowAnyException();
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(2));
    }

    // ---- rule (b): account names and institutions (step 2 review) -------------------------------------------------

    @Test
    void refusesAPasswordContainingAnAccountNameOrInstitution() {
        java.util.List<String> terms = java.util.List.of("Everyday Checking", "Northfield Credit Union", "Apex", "Visa");
        assertThatThrownBy(() -> REAL.checkPersonal("my everydaychecking 2024", terms))
                .hasMessageContaining("accounts or banks").message().doesNotContain("Everyday");
        assertThatThrownBy(() -> REAL.checkPersonal("NORTHFIELD-credit-union!!", terms))
                .isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> REAL.checkPersonal("love my apex card 77", terms))
                .isInstanceOf(VaultException.class);
        // Fewer than four letters and digits: not checked.
        assertThatCode(() -> REAL.checkPersonal("boa and td are too short to check", java.util.List.of("BoA", "TD")))
                .doesNotThrowAnyException();
        // Four exactly is checked.
        assertThatThrownBy(() -> REAL.checkPersonal("my visa card password", java.util.List.of("Visa")))
                .isInstanceOf(VaultException.class);
        assertThatCode(() -> REAL.checkPersonal("a perfectly unrelated passphrase", terms))
                .doesNotThrowAnyException();
        assertThatCode(() -> REAL.checkPersonal("anything at all here", java.util.List.of()))
                .doesNotThrowAnyException();
    }
}
