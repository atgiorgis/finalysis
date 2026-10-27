package com.finalysis.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finalysis.vault.VaultFakes.MutableVaultClock;
import com.finalysis.vault.VaultService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * The phone's login limits (docs/plans/phase-8.md §9.3): five wrong passwords per address, then a wait doubling from 30
 * seconds to 15 minutes; 20 wrong passwords in an hour from any addresses close the listener; a success resets its
 * address and gives a new session with the phone's role, lasting the auto-lock minutes.
 */
class LanLoginTest {

    private static final String RIGHT = "the vault password";

    private final VaultService vault = mock(VaultService.class);
    private final LanListener listener = mock(LanListener.class);
    private final MutableVaultClock clock = new MutableVaultClock();
    private LanLogin login;

    @BeforeEach
    void setUp() {
        when(vault.verifyPassword(anyString())).thenReturn(false);
        when(vault.verifyPassword(RIGHT)).thenReturn(true);
        when(vault.idleLockMinutes()).thenReturn(15);
        login = new LanLogin(vault, listener, clock);
    }

    @Test
    void aSuccessGivesANewSessionWithThePhonesRoleForTheAutoLockMinutes() {
        MockHttpServletRequest request = from("192.168.1.50");
        MockHttpSession planted = new MockHttpSession();
        request.setSession(planted);

        login.login(request, RIGHT);

        assertThat(planted.isInvalid()).as("a session planted before login is dropped").isTrue();
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        assertThat(session).isNotSameAs(planted);
        assertThat(session.getMaxInactiveInterval()).isEqualTo(15 * 60);
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getAuthorities()).extracting(Object::toString)
                .containsExactly("ROLE_PHONE");
    }

    @Test
    void fiveFreePerAddressThenTheWaitDoubles() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> login.login(from("192.168.1.50"), "wrong")).hasMessage("The password is wrong.");
        }
        assertThatThrownBy(() -> login.login(from("192.168.1.50"), RIGHT)).isInstanceOf(AccessException.class)
                .extracting(e -> ((AccessException) e).retryAfter()).isEqualTo(Duration.ofSeconds(30));
        // Another address isn't held up by this one.
        assertThatCode(() -> login.login(from("192.168.1.51"), RIGHT)).doesNotThrowAnyException();

        clock.advance(Duration.ofSeconds(30));
        assertThatThrownBy(() -> login.login(from("192.168.1.50"), "wrong again")).hasMessage("The password is wrong.");
        assertThatThrownBy(() -> login.login(from("192.168.1.50"), RIGHT))
                .extracting(e -> ((AccessException) e).retryAfter()).isEqualTo(Duration.ofSeconds(60));

        clock.advance(Duration.ofSeconds(60));
        assertThatCode(() -> login.login(from("192.168.1.50"), RIGHT)).doesNotThrowAnyException();
        assertThatThrownBy(() -> login.login(from("192.168.1.50"), "wrong after a success"))
                .hasMessage("The password is wrong.");
        verify(listener, never()).tooManyFailures();
    }

    @Test
    void twentyWrongInAnHourFromAnyAddressesCloseTheListener() {
        for (int i = 0; i < 19; i++) {
            String address = "192.168.1." + (100 + i / 4);
            assertThatThrownBy(() -> login.login(from(address), "guess")).isInstanceOf(AccessException.class);
        }
        verify(listener, never()).tooManyFailures();

        assertThatThrownBy(() -> login.login(from("192.168.1.200"), "guess 20")).isInstanceOf(AccessException.class);

        verify(listener, times(1)).tooManyFailures();
    }

    @Test
    void failuresOlderThanAnHourDontCount() {
        for (int i = 0; i < 19; i++) {
            String address = "192.168.1." + (100 + i / 4);
            assertThatThrownBy(() -> login.login(from(address), "guess")).isInstanceOf(AccessException.class);
        }
        clock.advance(Duration.ofMinutes(61));

        assertThatThrownBy(() -> login.login(from("192.168.1.200"), "guess")).isInstanceOf(AccessException.class);

        verify(listener, never()).tooManyFailures();
    }

    private static MockHttpServletRequest from(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/access/login");
        request.setRemoteAddr(address);
        request.setSecure(true);
        return request;
    }
}
