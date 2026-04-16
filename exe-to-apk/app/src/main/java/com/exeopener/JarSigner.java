package com.exeopener;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v1CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Applies V1 (JAR) signing to an APK represented as a map of entry-name → bytes.
 *
 * V1 signing adds three META-INF entries:
 *   META-INF/MANIFEST.MF  — SHA-256 digest of every non-META-INF entry
 *   META-INF/CERT.SF      — SHA-256 digest of each MANIFEST.MF section + whole manifest
 *   META-INF/CERT.RSA     — PKCS#7 SignedData block over CERT.SF
 *
 * A fresh RSA-2048 / self-signed cert is generated each time build() is called.
 * V1-only signing is valid on all Android versions when targetSdkVersion ≤ 28.
 */
public class JarSigner {

    private static final String HASH_ALG = "SHA-256";
    private static final String SIG_ALG  = "SHA256withRSA";

    private final Map<String, byte[]> entries; // ordered: non-META-INF entries

    public JarSigner(Map<String, byte[]> entries) {
        this.entries = entries;
    }

    /**
     * Writes all entries plus the three META-INF signing entries into {@code zos}.
     */
    public void sign(ZipOutputStream zos) throws Exception {
        // ---- 1. MANIFEST.MF ----
        StringBuilder mf = new StringBuilder();
        mf.append("Manifest-Version: 1.0\r\n");
        mf.append("Created-By: ExeToApk\r\n");
        mf.append("\r\n");

        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String digest = sha256b64(e.getValue());
            mf.append("Name: ").append(e.getKey()).append("\r\n");
            mf.append("SHA-256-Digest: ").append(digest).append("\r\n");
            mf.append("\r\n");
        }
        byte[] mfBytes = mf.toString().getBytes(StandardCharsets.UTF_8);

        // ---- 2. CERT.SF ----
        StringBuilder sf = new StringBuilder();
        sf.append("Signature-Version: 1.0\r\n");
        sf.append("Created-By: ExeToApk\r\n");
        sf.append("SHA-256-Digest-Manifest: ").append(sha256b64(mfBytes)).append("\r\n");
        sf.append("\r\n");

        // Re-parse manifest sections to compute per-entry digests over the section text
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String section = "Name: " + e.getKey() + "\r\n"
                    + "SHA-256-Digest: " + sha256b64(e.getValue()) + "\r\n"
                    + "\r\n";
            sf.append("Name: ").append(e.getKey()).append("\r\n");
            sf.append("SHA-256-Digest: ")
              .append(sha256b64(section.getBytes(StandardCharsets.UTF_8)))
              .append("\r\n");
            sf.append("\r\n");
        }
        byte[] sfBytes = sf.toString().getBytes(StandardCharsets.UTF_8);

        // ---- 3. Generate RSA-2048 key + self-signed cert ----
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        Date notBefore = new Date();
        Date notAfter  = new Date(System.currentTimeMillis() + 30L * 365 * 24 * 60 * 60 * 1000);
        X500Name dn = new X500Name("CN=ExeToApk Debug Signer,O=ExeToApk,C=US");

        JcaX509v1CertificateBuilder certBuilder =
                new JcaX509v1CertificateBuilder(dn, BigInteger.ONE, notBefore, notAfter, dn, kp.getPublic());
        ContentSigner contentSigner =
                new JcaContentSignerBuilder(SIG_ALG).build(kp.getPrivate());
        X509Certificate cert =
                new JcaX509CertificateConverter().getCertificate(certBuilder.build(contentSigner));

        // ---- 4. PKCS#7 / CMS SignedData block ----
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        gen.addSignerInfoGenerator(
                new JcaSignerInfoGeneratorBuilder(
                        new JcaDigestCalculatorProviderBuilder().build())
                        .build(contentSigner, cert));
        gen.addCertificate(new org.bouncycastle.cert.jcajce.JcaX509CertificateHolder(cert));

        byte[] certRsa = gen.generate(
                new CMSProcessableByteArray(sfBytes), false).getEncoded();

        // ---- 5. Write all entries + META-INF ----
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            writeStored(zos, e.getKey(), e.getValue());
        }
        writeStored(zos, "META-INF/MANIFEST.MF", mfBytes);
        writeStored(zos, "META-INF/CERT.SF",     sfBytes);
        writeStored(zos, "META-INF/CERT.RSA",    certRsa);
    }

    // ---- Helpers ----

    private static String sha256b64(byte[] data) throws Exception {
        byte[] hash = MessageDigest.getInstance(HASH_ALG).digest(data);
        return Base64.getEncoder().encodeToString(hash);
    }

    private static void writeStored(ZipOutputStream zos, String name, byte[] data) throws Exception {
        CRC32 crc = new CRC32();
        crc.update(data);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        entry.setCrc(crc.getValue());
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }
}
