package com.finalysis.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Phone access listens only on a private IPv4 address of this machine (docs/plans/phase-8.md §9.1). */
class LanAddressesTest {

    private final LanAddresses addresses = new LanAddresses(false);

    @Test
    void theThreePrivateRangesAndNothingElse() {
        assertThat(addresses.range("192.168.1.157").map(LanAddresses.Range::text)).contains("192.168.0.0/16");
        assertThat(addresses.range("10.20.30.40").map(LanAddresses.Range::text)).contains("10.0.0.0/8");
        assertThat(addresses.range("172.16.0.1").map(LanAddresses.Range::text)).contains("172.16.0.0/12");
        assertThat(addresses.range("172.31.255.254").map(LanAddresses.Range::text)).contains("172.16.0.0/12");
        for (String refused : new String[] {"172.15.0.1", "172.32.0.1", "8.8.8.8", "100.64.0.1", "169.254.1.1",
                "127.0.0.1", "0.0.0.0", "256.1.1.1", "192.168.1", "fe80::1", "localhost", "", null}) {
            assertThat(addresses.range(refused)).as(String.valueOf(refused)).isEmpty();
        }
    }

    @Test
    void loopbackOnlyForTests() {
        assertThat(new LanAddresses(true).range("127.0.0.1").map(LanAddresses.Range::text)).contains("127.0.0.0/8");
        assertThat(new LanAddresses(true).available()).contains("127.0.0.1");
        assertThat(addresses.available()).doesNotContain("127.0.0.1");
    }

    @Test
    void namesAreNeverLookedUp() {
        assertThat(LanAddresses.parse("router.local")).isNull();
        assertThat(LanAddresses.parse("192.168.001.010")).containsExactly(192, 168, 1, 10);
    }
}
