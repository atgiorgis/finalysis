package com.finalysis.access;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.GeneralSubtree;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.NameConstraints;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * The LAN listener's certificates (docs/plans/phase-8.md §9.2), kept in {@code <mount>/tls/} (inside the vault, 0600):
 *
 * <ul>
 *   <li>a <b>local CA</b>: ECDSA P-256, 2 years, a CA for servers only (path length 0), with a <b>name constraint</b>
 *       permitting only the private range the address is in, so even a leaked CA key couldn't vouch for another site;</li>
 *   <li>a <b>server certificate</b> for the chosen IP (subject alternative name), signed by it, 1 year (under the 398
 *       days browsers accept).</li>
 * </ul>
 *
 * Both are reused; the CA is made again when the range changes or it has 30 days left (the phone then installs the new
 * one), the server certificate when the address changes or it has 30 days left.
 */
final class LanCertificates {

    static final Duration CA_LIFETIME = Duration.ofDays(730);
    static final Duration SERVER_LIFETIME = Duration.ofDays(365);
    static final Duration RENEW_BEFORE = Duration.ofDays(30);

    /** The CA and the server certificate with their keys, as the listener uses them. */
    record Material(X509Certificate ca, PrivateKey caKey, X509Certificate server, PrivateKey serverKey) {
        @Override
        public String toString() {
            return "LanCertificates.Material[" + server.getSubjectX500Principal() + "]";
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private LanCertificates() {
    }

    /** The certificates for this address: the stored ones when still right, new ones otherwise. */
    static Material ensure(Path dir, String ip, LanAddresses.Range range, Instant now) {
        try {
            Files.createDirectories(dir);
            Optional<X509Certificate> storedCa = readCertificate(dir.resolve("ca.cer.pem"));
            Optional<PrivateKey> storedCaKey = readKey(dir.resolve("ca.key.pem"));
            X509Certificate ca;
            PrivateKey caKey;
            boolean newCa = storedCa.isEmpty() || storedCaKey.isEmpty() || !permits(storedCa.get(), range)
                    || expiresSoon(storedCa.get(), now);
            if (newCa) {
                KeyPair pair = newKeyPair();
                ca = caCertificate(pair, range, now);
                caKey = pair.getPrivate();
                writeOwnerOnly(dir.resolve("ca.key.pem"), pemKey(pair.getPrivate()));
                writeOwnerOnly(dir.resolve("ca.cer.pem"), pem(ca));
            } else {
                ca = storedCa.get();
                caKey = storedCaKey.get();
            }
            Optional<X509Certificate> storedServer = readCertificate(dir.resolve("server.cer.pem"));
            Optional<PrivateKey> storedServerKey = readKey(dir.resolve("server.key.pem"));
            if (!newCa && storedServer.isPresent() && storedServerKey.isPresent()
                    && namesIp(storedServer.get(), ip) && signedBy(storedServer.get(), ca)
                    && !expiresSoon(storedServer.get(), now)) {
                return new Material(ca, caKey, storedServer.get(), storedServerKey.get());
            }
            KeyPair pair = newKeyPair();
            X509Certificate server = serverCertificate(pair, ip, ca, caKey, now);
            writeOwnerOnly(dir.resolve("server.key.pem"), pemKey(pair.getPrivate()));
            writeOwnerOnly(dir.resolve("server.cer.pem"), pem(server));
            return new Material(ca, caKey, server, pair.getPrivate());
        } catch (IOException e) {
            throw new UncheckedIOException("the LAN certificates could not be stored", e);
        } catch (GeneralSecurityException | org.bouncycastle.operator.OperatorCreationException e) {
            throw new IllegalStateException("the LAN certificates could not be made", e);
        }
    }

    /** The server key and chain in an in-memory PKCS12 store, for Tomcat. */
    static KeyStore keyStore(Material material, char[] password) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("lan", material.serverKey(), password, new Certificate[] {material.server(), material.ca()});
        return store;
    }

    /** The CA as the phone installs it (DER, a {@code .cer} file). */
    static byte[] caDer(X509Certificate ca) {
        try {
            return ca.getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 of the CA, colon-separated upper-case hex, as iOS and Android show it. */
    static String fingerprint(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- building --------------------------------------------------------------------------------------------------

    private static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return generator.generateKeyPair();
    }

    private static X509Certificate caCertificate(KeyPair pair, LanAddresses.Range range, Instant now)
            throws GeneralSecurityException, IOException, org.bouncycastle.operator.OperatorCreationException {
        X500Name name = new X500Name("CN=Finalysis local CA (" + range.text() + ")");
        JcaX509ExtensionUtils utils = new JcaX509ExtensionUtils();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, serial(),
                Date.from(now.minus(Duration.ofMinutes(5))), Date.from(now.plus(CA_LIFETIME)), name, pair.getPublic())
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(0))
                .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign))
                .addExtension(Extension.nameConstraints, true, new NameConstraints(
                        new GeneralSubtree[] {new GeneralSubtree(new GeneralName(GeneralName.iPAddress,
                                range.text()))}, null))
                .addExtension(Extension.subjectKeyIdentifier, false, utils.createSubjectKeyIdentifier(pair.getPublic()));
        return sign(builder, pair.getPrivate());
    }

    private static X509Certificate serverCertificate(KeyPair pair, String ip, X509Certificate ca, PrivateKey caKey,
                                                     Instant now)
            throws GeneralSecurityException, IOException, org.bouncycastle.operator.OperatorCreationException {
        JcaX509ExtensionUtils utils = new JcaX509ExtensionUtils();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(ca, serial(),
                Date.from(now.minus(Duration.ofMinutes(5))), Date.from(now.plus(SERVER_LIFETIME)),
                new X500Name("CN=" + ip), pair.getPublic())
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
                .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature))
                .addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
                .addExtension(Extension.subjectAlternativeName, false,
                        new GeneralNames(new GeneralName(GeneralName.iPAddress, ip)))
                .addExtension(Extension.authorityKeyIdentifier, false, utils.createAuthorityKeyIdentifier(ca))
                .addExtension(Extension.subjectKeyIdentifier, false, utils.createSubjectKeyIdentifier(pair.getPublic()));
        return sign(builder, caKey);
    }

    private static X509Certificate sign(X509v3CertificateBuilder builder, PrivateKey key)
            throws GeneralSecurityException, org.bouncycastle.operator.OperatorCreationException {
        return new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(key)));
    }

    private static BigInteger serial() {
        return new BigInteger(127, RANDOM).add(BigInteger.ONE);
    }

    // ---- checking what is stored -----------------------------------------------------------------------------------

    /** The CA's name constraint is exactly this range. */
    static boolean permits(X509Certificate ca, LanAddresses.Range range) {
        byte[] extension = ca.getExtensionValue(Extension.nameConstraints.getId());
        if (extension == null) {
            return false;
        }
        try {
            NameConstraints constraints = NameConstraints.getInstance(
                    ASN1Primitive.fromByteArray(DEROctetString.getInstance(extension).getOctets()));
            GeneralSubtree[] permitted = constraints.getPermittedSubtrees();
            GeneralName expected = new GeneralName(GeneralName.iPAddress, range.text());
            return permitted != null && permitted.length == 1 && permitted[0].getBase().equals(expected);
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    static boolean namesIp(X509Certificate server, String ip) {
        try {
            Collection<List<?>> names = server.getSubjectAlternativeNames();
            return names != null && names.stream()
                    .anyMatch(n -> Integer.valueOf(GeneralName.iPAddress).equals(n.get(0)) && ip.equals(n.get(1)));
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static boolean signedBy(X509Certificate server, X509Certificate ca) {
        try {
            server.verify(ca.getPublicKey());
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static boolean expiresSoon(X509Certificate certificate, Instant now) {
        return certificate.getNotAfter().toInstant().isBefore(now.plus(RENEW_BEFORE));
    }

    // ---- PEM files -------------------------------------------------------------------------------------------------

    private static String pem(Object object) throws IOException {
        StringWriter text = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(text)) {
            writer.writeObject(object);
        }
        return text.toString();
    }

    /** A private key as PKCS#8 ("PRIVATE KEY"), which reads back on every JDK. */
    private static String pemKey(PrivateKey key) throws IOException {
        return pem(new JcaPKCS8Generator(key, null).generate());
    }

    private static Optional<X509Certificate> readCertificate(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (PEMParser parser = new PEMParser(new StringReader(Files.readString(file, StandardCharsets.US_ASCII)))) {
            Object read = parser.readObject();
            return read instanceof X509CertificateHolder holder
                    ? Optional.of(new JcaX509CertificateConverter().getCertificate(holder))
                    : Optional.empty();
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<PrivateKey> readKey(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (PEMParser parser = new PEMParser(new StringReader(Files.readString(file, StandardCharsets.US_ASCII)))) {
            Object read = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (read instanceof PEMKeyPair pair) {
                return Optional.of(converter.getKeyPair(pair).getPrivate());
            }
            if (read instanceof PrivateKeyInfo info) {
                return Optional.of(converter.getPrivateKey(info));
            }
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static void writeOwnerOnly(Path file, String text) throws IOException {
        Path temp = file.resolveSibling("." + file.getFileName() + ".tmp");
        Files.deleteIfExists(temp);
        Files.createFile(temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(temp, text, StandardCharsets.US_ASCII);
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
