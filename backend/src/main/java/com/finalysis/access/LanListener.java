package com.finalysis.access;

import com.finalysis.vault.VaultFiles;
import com.finalysis.vault.VaultLocking;
import com.finalysis.vault.VaultUnlocked;
import jakarta.servlet.http.HttpServletRequest;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;

/**
 * The LAN listener (docs/plans/phase-8.md §9.1): a second Tomcat connector, HTTPS only, on the chosen private address,
 * next to {@code 127.0.0.1:8080}. Open only while the vault is unlocked and the setting is on; it opens on unlock and
 * closes on lock (after the lock's own response, which may be the phone's), and stays closed after 20 failed logins in
 * an hour until the next local unlock. If the address is gone (another network), it doesn't open and says why.
 */
public class LanListener {

    private static final Logger log = LoggerFactory.getLogger(LanListener.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What Settings shows; never a path. */
    public record Status(boolean enabled, String address, int port, boolean open, String reason, String url,
                         String caFingerprint, List<String> availableAddresses, int sessions) {
    }

    private final LanSettings settings;
    private final LanAddresses addresses;
    private final LanSessions sessions;
    private final VaultFiles vaultFiles;

    private volatile Tomcat tomcat;
    private volatile Connector connector;
    /** The open listener's address as typed: Tomcat hands it back as an InetAddress, which prints as "/1.2.3.4". */
    private volatile String openAddress;
    private volatile String reason;
    private volatile boolean blockedUntilUnlock;
    private volatile X509Certificate ca;

    LanListener(LanSettings settings, LanAddresses addresses, LanSessions sessions, VaultFiles vaultFiles) {
        this.settings = settings;
        this.addresses = addresses;
        this.sessions = sessions;
        this.vaultFiles = vaultFiles;
    }

    @EventListener
    void onWebServer(WebServerInitializedEvent event) {
        if (event.getWebServer() instanceof TomcatWebServer web && "server".equals(namespace(event))) {
            tomcat = web.getTomcat();
            if (vaultFiles.tlsDirectory().isPresent()) {
                open();
            }
        }
    }

    private static String namespace(WebServerInitializedEvent event) {
        String ns = event.getApplicationContext().getServerNamespace();
        return ns == null ? "server" : ns;
    }

    @EventListener(VaultUnlocked.class)
    void onUnlocked() {
        blockedUntilUnlock = false;
        open();
    }

    /** Lock (§4.6): every session ends now; the listener closes right after, off the request thread. */
    @EventListener(VaultLocking.class)
    void onLocking() {
        int ended = sessions.invalidateAll();
        if (ended > 0) {
            log.info("Phone sessions ended by the lock: {}", ended);
        }
        ca = null;
        closeSoon();
    }

    /** Opens the listener when the setting is on and everything it needs is there; otherwise records why not. */
    synchronized void open() {
        closeNow(connector);
        Optional<java.nio.file.Path> tls = vaultFiles.tlsDirectory();
        if (tls.isEmpty()) {
            reason = "locked";
            return;
        }
        LanSettings.Setting setting = settings.read();
        if (!setting.enabled()) {
            reason = null;
            return;
        }
        if (blockedUntilUnlock) {
            reason = "too-many-failures";
            return;
        }
        if (tomcat == null) {
            reason = "no-server";
            return;
        }
        Optional<LanAddresses.Range> range = addresses.range(setting.address());
        if (range.isEmpty() || !addresses.available().contains(setting.address())) {
            reason = "address-gone";
            log.warn("Phone access is on, but its address is not on this machine now; the listener stays closed");
            return;
        }
        try {
            LanCertificates.Material material = LanCertificates.ensure(tls.get(), setting.address(), range.get(),
                    Instant.now());
            ca = material.ca();
            char[] password = Base64.getEncoder().encodeToString(randomBytes()).toCharArray();
            KeyStore store = LanCertificates.keyStore(material, password);
            Connector lan = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            lan.setPort(setting.port());
            lan.setProperty("address", setting.address());
            lan.setScheme("https");
            lan.setSecure(true);
            lan.setProperty("SSLEnabled", "true");
            SSLHostConfig ssl = new SSLHostConfig();
            ssl.setProtocols("TLSv1.2+TLSv1.3");
            SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(ssl, SSLHostConfigCertificate.Type.EC);
            certificate.setCertificateKeystore(store);
            certificate.setCertificateKeystorePassword(new String(password));
            certificate.setCertificateKeyAlias("lan");
            ssl.addCertificate(certificate);
            lan.addSslHostConfig(ssl);
            tomcat.getService().addConnector(lan);
            if (lan.getState() != LifecycleState.STARTED) {
                tomcat.getService().removeConnector(lan);
                reason = "listener-failed";
                log.error("The phone listener did not start (is the port in use?)");
                return;
            }
            openAddress = setting.address();
            connector = lan;
            reason = null;
            log.info("Phone access open on its LAN address, port {}", setting.port());
        } catch (Exception e) {
            reason = "listener-failed";
            log.error("The phone listener did not start ({})", e.getClass().getName());
        }
    }

    /** Closes the listener on another thread, so a lock sent from the phone gets its answer first. */
    void closeSoon() {
        Connector closing = connector;
        if (closing == null) {
            return;
        }
        Thread.ofVirtual().name("lan-listener-close").start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            closeNow(closing);
        });
    }

    /** Closes this connector if it is still the open one. */
    synchronized void closeNow(Connector closing) {
        if (closing == null || closing != connector) {
            return;
        }
        connector = null;
        try {
            tomcat.getService().removeConnector(closing);
            closing.destroy();
            log.info("Phone access closed");
        } catch (Exception e) {
            log.error("Closing the phone listener failed ({})", e.getClass().getName());
        }
    }

    /** 20 failed logins in an hour (§9.3): closed until the next local unlock. */
    void tooManyFailures() {
        blockedUntilUnlock = true;
        reason = "too-many-failures";
        sessions.invalidateAll();
        log.warn("Phone access closed after too many failed logins; it opens again at the next unlock");
        closeSoon();
    }

    /** True for a request that came in on the LAN listener. */
    public boolean isLanRequest(HttpServletRequest request) {
        Connector open = connector;
        return open != null && request.isSecure() && request.getLocalPort() == open.getPort();
    }

    /** The LAN origin, for the Origin and Host checks; empty while closed. */
    Optional<String> origin() {
        Connector open = connector;
        if (open == null) {
            return Optional.empty();
        }
        return Optional.of("https://" + openAddress + ":" + open.getPort());
    }

    Optional<String> address() {
        return connector == null ? Optional.empty() : Optional.ofNullable(openAddress);
    }

    /** The CA as the phone installs it; only while unlocked and once made. */
    Optional<byte[]> caCertificate() {
        X509Certificate current = ca;
        return current == null ? Optional.empty() : Optional.of(LanCertificates.caDer(current));
    }

    Status status() {
        LanSettings.Setting setting = vaultFiles.tlsDirectory().isPresent() ? settings.read() : null;
        Connector open = connector;
        X509Certificate current = ca;
        return new Status(setting != null && setting.enabled(), setting == null ? null : setting.address(),
                setting == null ? 0 : setting.port(), open != null, reason,
                open == null ? null : "https://" + openAddress + ":" + open.getPort(),
                current == null ? null : LanCertificates.fingerprint(current), addresses.available(), sessions.count());
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
