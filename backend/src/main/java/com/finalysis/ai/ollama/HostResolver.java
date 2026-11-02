package com.finalysis.ai.ollama;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Looks up a host name's addresses for Ollama's requests (docs/plans/phase-9.md, 9c-8 notes). The system's resolver in
 * the app; tests give their own, so no test looks up a real name.
 */
@FunctionalInterface
public interface HostResolver {

    HostResolver SYSTEM = InetAddress::getAllByName;

    /** @throws UnknownHostException when the name has no address */
    InetAddress[] resolve(String host) throws UnknownHostException;
}
