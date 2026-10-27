package com.finalysis.access;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Phone access ({@code finalysis.access.*}, docs/plans/phase-8.md §9). Whether it is on, and on which address and port,
 * is the user's setting (app_setting, Settings → Phone access); these are the fixed parts.
 *
 * @param uiDir         the built frontend ({@code npm run build}) served to the phone, relative to the working directory
 * @param defaultPort   the LAN port offered first (1024 to 65535)
 * @param loopbackForTests also accept 127.0.0.1 as a "LAN" address: tests only, so no test opens a port on the network
 */
@ConfigurationProperties("finalysis.access")
public record AccessProperties(@DefaultValue("../frontend/dist") String uiDir,
                               @DefaultValue("8443") int defaultPort,
                               @DefaultValue("false") boolean loopbackForTests) {

    public AccessProperties {
        if (defaultPort < 1024 || defaultPort > 65535) {
            throw new IllegalArgumentException("finalysis.access.default-port must be between 1024 and 65535");
        }
    }
}
