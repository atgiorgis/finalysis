package com.finalysis.access;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Which addresses phone access may listen on (docs/plans/phase-8.md §9.1): this machine's private IPv4 addresses
 * (10/8, 172.16/12, 192.168/16) on interfaces that are up; anything else is refused. The CA's name constraint is the
 * whole private range the address is in (§9.2).
 */
class LanAddresses {

    /** A private range: its network address and prefix length. */
    record Range(byte[] network, int prefix) {
        String text() {
            try {
                return InetAddress.getByAddress(network).getHostAddress() + "/" + prefix;
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private final boolean loopbackForTests;

    LanAddresses(boolean loopbackForTests) {
        this.loopbackForTests = loopbackForTests;
    }

    /** This machine's private IPv4 addresses, sorted; 127.0.0.1 too in tests. */
    List<String> available() {
        List<String> found = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address v4 && range(v4.getHostAddress()).isPresent()
                            && (!v4.isLoopbackAddress() || loopbackForTests)) {
                        found.add(v4.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            return List.of();
        }
        return found.stream().distinct().sorted().toList();
    }

    /** The private range an IPv4 address is in; empty for anything else (public, IPv6, a name, malformed). */
    Optional<Range> range(String address) {
        byte[] b = parse(address);
        if (b == null) {
            return Optional.empty();
        }
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        if (first == 10) {
            return Optional.of(new Range(new byte[] {10, 0, 0, 0}, 8));
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return Optional.of(new Range(new byte[] {(byte) 172, 16, 0, 0}, 12));
        }
        if (first == 192 && second == 168) {
            return Optional.of(new Range(new byte[] {(byte) 192, (byte) 168, 0, 0}, 16));
        }
        if (first == 127 && loopbackForTests) {
            return Optional.of(new Range(new byte[] {127, 0, 0, 0}, 8));
        }
        return Optional.empty();
    }

    /** Four dotted decimal octets only: no names, so nothing is looked up. */
    static byte[] parse(String address) {
        if (address == null || !address.matches("(\\d{1,3}\\.){3}\\d{1,3}")) {
            return null;
        }
        String[] parts = address.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            int value = Integer.parseInt(parts[i]);
            if (value > 255) {
                return null;
            }
            bytes[i] = (byte) value;
        }
        return bytes;
    }
}
