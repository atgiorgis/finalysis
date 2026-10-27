package com.finalysis.vault;

import com.finalysis.support.TestcontainersConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The app with the vault on (docs/plans/phase-8.md §10): real state machine, key file, gate and endpoints; fakes for
 * the disk image and the database runtime ({@link VaultFakes}); a vault directory under {@code target/}. Every class
 * with it shares one context, so each test starts from {@link VaultTestState#reset}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(properties = {
        "finalysis.vault.enabled=true",
        "finalysis.vault.dir=${user.dir}/target/vault-test-${random.uuid}",
        "finalysis.vault.idle-lock-minutes=15"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, VaultFakes.Config.class})
@Testcontainers(disabledWithoutDocker = true)
public @interface VaultEnabledTest {
}
