package com.finalysis.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPairGenerator;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The LAN listener's certificates (docs/plans/phase-8.md §9.2): a CA limited by a name constraint to the private range,
 * a server certificate for the IP, reused while right, renewed when the address or range changes; files owner-only.
 * The name constraint is checked the way a client would: a certificate for an address outside the range, signed by the
 * CA's key, fails PKIX validation.
 */
class LanCertificatesTest {

    private static final LanAddresses ADDRESSES = new LanAddresses(false);
    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    @TempDir
    Path tls;

    @Test
    void aConstrainedCaAndAServerCertificateForTheIp() throws Exception {
        LanCertificates.Material m = LanCertificates.ensure(tls, "192.168.1.157", range("192.168.1.157"), NOW);

        assertThat(m.ca().getBasicConstraints()).isZero();
        assertThat(m.ca().getKeyUsage()[5]).as("keyCertSign").isTrue();
        assertThat(m.ca().getCriticalExtensionOIDs()).contains(Extension.nameConstraints.getId());
        assertThat(LanCertificates.permits(m.ca(), range("192.168.1.157"))).isTrue();
        assertThat(m.ca().getPublicKey().getAlgorithm()).isEqualTo("EC");
        assertThat(Duration.between(NOW, m.ca().getNotAfter().toInstant())).isEqualTo(LanCertificates.CA_LIFETIME);

        assertThat(m.server().getBasicConstraints()).isEqualTo(-1);
        assertThat(LanCertificates.namesIp(m.server(), "192.168.1.157")).isTrue();
        assertThat(m.server().getExtendedKeyUsage()).containsExactly("1.3.6.1.5.5.7.3.1");
        assertThat(Duration.between(NOW, m.server().getNotAfter().toInstant())).isLessThanOrEqualTo(Duration.ofDays(398));
        m.server().verify(m.ca().getPublicKey());
        validate(m.server(), m.ca());

        assertThat(LanCertificates.fingerprint(m.ca())).matches("([0-9A-F]{2}:){31}[0-9A-F]{2}");
        try (Stream<Path> files = Files.list(tls)) {
            for (Path file : files.toList()) {
                assertThat(Files.getPosixFilePermissions(file)).as(file.getFileName().toString())
                        .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            }
        }
    }

    @Test
    void theNameConstraintStopsTheCaVouchingForAnythingElse() throws Exception {
        LanCertificates.Material m = LanCertificates.ensure(tls, "192.168.1.157", range("192.168.1.157"), NOW);
        X509Certificate outside = signedByCa(m, "8.8.8.8");

        assertThatThrownBy(() -> validate(outside, m.ca())).isInstanceOf(CertPathValidatorException.class);
        validate(signedByCa(m, "192.168.200.9"), m.ca());
    }

    @Test
    void reusedWhileRightRenewedWhenTheAddressOrRangeChanges() {
        LanCertificates.Material first = LanCertificates.ensure(tls, "192.168.1.157", range("192.168.1.157"), NOW);
        LanCertificates.Material again = LanCertificates.ensure(tls, "192.168.1.157", range("192.168.1.157"), NOW);
        assertThat(again.ca()).isEqualTo(first.ca());
        assertThat(again.server()).isEqualTo(first.server());

        LanCertificates.Material moved = LanCertificates.ensure(tls, "192.168.1.20", range("192.168.1.20"), NOW);
        assertThat(moved.ca()).as("same range, same CA: the phone keeps it").isEqualTo(first.ca());
        assertThat(moved.server()).isNotEqualTo(first.server());
        assertThat(LanCertificates.namesIp(moved.server(), "192.168.1.20")).isTrue();

        LanCertificates.Material otherRange = LanCertificates.ensure(tls, "10.0.0.5", range("10.0.0.5"), NOW);
        assertThat(otherRange.ca()).isNotEqualTo(first.ca());
        assertThat(LanCertificates.permits(otherRange.ca(), range("10.0.0.5"))).isTrue();

        LanCertificates.Material late = LanCertificates.ensure(tls, "10.0.0.5", range("10.0.0.5"),
                NOW.plus(LanCertificates.SERVER_LIFETIME).minus(Duration.ofDays(10)));
        assertThat(late.server()).as("renewed 30 days before it ends").isNotEqualTo(otherRange.server());
    }

    @Test
    void aDamagedFileIsReplacedNotTrusted() throws Exception {
        LanCertificates.Material first = LanCertificates.ensure(tls, "192.168.1.157", range("192.168.1.157"), NOW);
        Files.writeString(tls.resolve("ca.cer.pem"), "not a certificate");

        LanCertificates.Material next = LanCertificates.ensure(tls, "192.168.1.157", range("192.168.1.157"), NOW);

        assertThat(next.ca()).isNotEqualTo(first.ca());
        validate(next.server(), next.ca());
    }

    private static LanAddresses.Range range(String ip) {
        return ADDRESSES.range(ip).orElseThrow();
    }

    /**
     * PKIX validation that enforces the CA's name constraint. Phones and Chrome enforce a root's own constraints; the
     * JDK's validator doesn't take them from a trust anchor at all. So the CA is issued again, the same key and the same
     * extensions, under a throwaway test root: its name constraint is then part of the path, which PKIX must enforce.
     */
    private static void validate(X509Certificate server, X509Certificate ca) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var root = generator.generateKeyPair();
        X500Name rootName = new X500Name("CN=Test root");
        X509Certificate rootCertificate = new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(rootName, BigInteger.ONE, Date.from(NOW.minus(Duration.ofDays(1))),
                        Date.from(NOW.plus(Duration.ofDays(3650))), rootName, root.getPublic())
                        .addExtension(Extension.basicConstraints, true,
                                new org.bouncycastle.asn1.x509.BasicConstraints(1))
                        .build(new JcaContentSignerBuilder("SHA256withECDSA").build(root.getPrivate())));
        var intermediate = new JcaX509v3CertificateBuilder(rootName, BigInteger.TWO, ca.getNotBefore(),
                ca.getNotAfter(), X500Name.getInstance(ca.getSubjectX500Principal().getEncoded()), ca.getPublicKey());
        for (String oid : List.of(Extension.basicConstraints.getId(), Extension.keyUsage.getId(),
                Extension.nameConstraints.getId(), Extension.subjectKeyIdentifier.getId())) {
            byte[] value = ca.getExtensionValue(oid);
            intermediate.addExtension(new org.bouncycastle.asn1.ASN1ObjectIdentifier(oid),
                    ca.getCriticalExtensionOIDs().contains(oid),
                    org.bouncycastle.asn1.DEROctetString.getInstance(value).getOctets());
        }
        X509Certificate caUnderRoot = new JcaX509CertificateConverter().getCertificate(
                intermediate.build(new JcaContentSignerBuilder("SHA256withECDSA").build(root.getPrivate())));
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(rootCertificate, null)));
        parameters.setRevocationEnabled(false);
        parameters.setDate(Date.from(NOW.plus(Duration.ofDays(1))));
        CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(List.of(server, caUnderRoot)),
                parameters);
    }

    /** What a leaked CA key could sign: a server certificate for any address. */
    private static X509Certificate signedByCa(LanCertificates.Material m, String ip) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        var builder = new JcaX509v3CertificateBuilder(m.ca(), BigInteger.valueOf(42), Date.from(NOW),
                Date.from(NOW.plus(Duration.ofDays(30))), new X500Name("CN=" + ip), pair.getPublic())
                .addExtension(Extension.subjectAlternativeName, false,
                        new GeneralNames(new GeneralName(GeneralName.iPAddress, ip)));
        return new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(m.caKey())));
    }
}
