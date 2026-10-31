import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Offline APK packager used by scripts/build-offline.sh (Android Studio users do
 * not need this). Merges the aapt2-linked resources APK with the D8 dex files,
 * then signs (v1+v2+v3) and verifies the result with Google's apksig library.
 *
 * Usage: ApkPackager <resources.apk> <dexDir> <out.apk> <keystore.p12> <alias> <minSdk>
 * The keystore password is read from the WORKDECK_KS_PASS environment variable.
 */
public final class ApkPackager {
    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            System.err.println("usage: ApkPackager <resources.apk> <dexDir> <out.apk> <keystore.p12> <alias> <minSdk>");
            System.exit(2);
        }
        File base = new File(args[0]);
        File dexDir = new File(args[1]);
        File out = new File(args[2]);
        File ks = new File(args[3]);
        String alias = args[4];
        int minSdk = Integer.parseInt(args[5]);
        String pass = System.getenv("WORKDECK_KS_PASS");
        if (pass == null || pass.isEmpty()) throw new IllegalStateException("WORKDECK_KS_PASS is not set");

        File unsigned = new File(out.getParentFile(), out.getName() + ".unsigned");
        merge(base, dexDir, unsigned);

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(ks)) {
            store.load(in, pass.toCharArray());
        }
        PrivateKey key = (PrivateKey) store.getKey(alias, pass.toCharArray());
        X509Certificate cert = (X509Certificate) store.getCertificate(alias);
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                "WORKDECK", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(unsigned)
                .setOutputApk(out)
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
        //noinspection ResultOfMethodCallIgnored
        unsigned.delete();

        ApkVerifier.Result r = new ApkVerifier.Builder(out).build().verify();
        System.out.println("verified=" + r.isVerified() + " v1=" + r.isVerifiedUsingV1Scheme()
                + " v2=" + r.isVerifiedUsingV2Scheme() + " v3=" + r.isVerifiedUsingV3Scheme());
        for (ApkVerifier.IssueWithParams e : r.getErrors()) System.out.println("ERROR: " + e);
        if (!r.isVerified()) System.exit(1);
    }

    private static void merge(File base, File dexDir, File out) throws Exception {
        try (ZipFile zf = new ZipFile(base);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            byte[] buf = new byte[64 * 1024];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.getName().endsWith(".dex")) continue;
                ZipEntry ne = new ZipEntry(e.getName());
                ne.setTime(315532800000L);
                if (e.getMethod() == ZipEntry.STORED) {
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(e.getSize());
                    ne.setCompressedSize(e.getSize());
                    ne.setCrc(e.getCrc());
                }
                zos.putNextEntry(ne);
                try (InputStream in = zf.getInputStream(e)) {
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
            File[] dexes = dexDir.listFiles((d, n) -> n.endsWith(".dex"));
            if (dexes == null || dexes.length == 0) throw new IllegalStateException("no dex files in " + dexDir);
            Arrays.sort(dexes);
            for (File dex : dexes) {
                byte[] data = java.nio.file.Files.readAllBytes(dex.toPath());
                ZipEntry ne = new ZipEntry(dex.getName());
                ne.setTime(315532800000L);
                zos.putNextEntry(ne);
                zos.write(data);
                zos.closeEntry();
            }
        }
    }
}
