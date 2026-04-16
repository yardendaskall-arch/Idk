package com.exeopener;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v1CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * V1 (JAR) signing for the wrapper APK.
 *
 * Small entries (template DEX, manifest, resources) are held as byte[] in {@code entries}.
 * The EXE is kept on disk in {@code exeFile}; only its pre-computed SHA-256
 * {@code exeDigest} is needed in memory to build the signing manifests. The file
 * is then streamed directly into the ZIP — no heap allocation proportional to EXE size.
 */
public class JarSigner {

    private static final String EXE_ENTRY = "assets/payload.exe";
    private static final String SIG_ALG   = "SHA256withRSA";

    private final Map<String, byte[]> entries;  // all template entries (small)
    private final File   exeFile;               // EXE on disk
    private final byte[] exeDigest;             // pre-computed SHA-256 of EXE

    public JarSigner(Map<String, byte[]> entries, File exeFile, byte[] exeDigest) {
        this.entries   = entries;
        this.exeFile   = exeFile;
        this.exeDigest = exeDigest;
    }

    public void sign(ZipOutputStream zos) throws Exception {
        // ---- 1. MANIFEST.MF ----
        StringBuilder mf = new StringBuilder();
        mf.append("Manifest-Version: 1.0\r\nCreated-By: ExeToApk\r\n\r\n");
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            appendSection(mf, e.getKey(), sha256b64(e.getValue()));
        }
        appendSection(mf, EXE_ENTRY, sha256b64(exeDigest));
        byte[] mfBytes = mf.toString().getBytes(StandardCharsets.UTF_8);

        // ---- 2. CERT.SF ----
        StringBuilder sf = new StringBuilder();
        sf.append("Signature-Version: 1.0\r\n");
        sf.append("Created-By: ExeToApk\r\n");
        sf.append("SHA-256-Digest-Manifest: ").append(sha256b64(mfBytes)).append("\r\n\r\n");
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String section = "Name: " + e.getKey() + "\r\nSHA-256-Digest: "
                    + sha256b64(e.getValue()) + "\r\n\r\n";
            appendSection(sf, e.getKey(),
                    sha256b64(section.getBytes(StandardCharsets.UTF_8)));
        }
        String exeSection = "Name: " + EXE_ENTRY + "\r\nSHA-256-Digest: "
                + sha256b64(exeDigest) + "\r\n\r\n";
        appendSection(sf, EXE_ENTRY,
                sha256b64(exeSection.getBytes(StandardCharsets.UTF_8)));
        byte[] sfBytes = sf.toString().getBytes(StandardCharsets.UTF_8);

        // ---- 3. RSA key + self-signed cert ----
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        Date notBefore = new Date();
        Date notAfter  = new Date(System.currentTimeMillis() + 30L * 365 * 24 * 60 * 60 * 1000);
        X500Name dn = new X500Name("CN=ExeToApk,O=ExeToApk,C=US");
        ContentSigner contentSigner =
                new JcaContentSignerBuilder(SIG_ALG).build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(new JcaX509v1CertificateBuilder(
                        dn, BigInteger.ONE, notBefore, notAfter, dn, kp.getPublic())
                        .build(contentSigner));

        // ---- 4. PKCS#7 block ----
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        gen.addSignerInfoGenerator(
                new JcaSignerInfoGeneratorBuilder(
                        new JcaDigestCalculatorProviderBuilder().build())
                        .build(contentSigner, cert));
        gen.addCertificate(new JcaX509CertificateHolder(cert));
        byte[] certRsa = gen.generate(
                new CMSProcessableByteArray(sfBytes), false).getEncoded();

        // ---- 5. Write entries ----
        // Small template entries
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            writeStored(zos, e.getKey(), e.getValue());
        }
        // EXE — streamed from disk, DEFLATED so no need for pre-known size/CRC
        writeDeflated(zos, EXE_ENTRY, exeFile);
        // Signing entries
        writeStored(zos, "META-INF/MANIFEST.MF", mfBytes);
        writeStored(zos, "META-INF/CERT.SF",     sfBytes);
        writeStored(zos, "META-INF/CERT.RSA",    certRsa);
    }

    // ---- helpers ----

    private static void appendSection(StringBuilder sb, String name, String digest) {
        sb.append("Name: ").append(name).append("\r\n");
        sb.append("SHA-256-Digest: ").append(digest).append("\r\n");
        sb.append("\r\n");
    }

    private static String sha256b64(byte[] data) throws Exception {
        return Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static void writeStored(ZipOutputStream zos, String name, byte[] data)
            throws IOException {
        CRC32 crc = new CRC32();
        crc.update(data);
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        e.setCrc(crc.getValue());
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    /** Streams a File into the ZIP with DEFLATE compression (no pre-buffering). */
    private static void writeDeflated(ZipOutputStream zos, String name, File file)
            throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.DEFLATED);
        zos.putNextEntry(e);
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) != -1) zos.write(buf, 0, n);
        }
        zos.closeEntry();
    }
}
