package com.limelight.binding.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

public class AndroidCryptoProviderTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Context contextFor(File directory) {
        Context context = mock(Context.class);
        when(context.getFilesDir()).thenReturn(directory);
        return context;
    }

    @Test
    public void generatedCredentialsUseInteroperablePemAndPkcs8Formats() throws Exception {
        AndroidCryptoProvider provider = new AndroidCryptoProvider(
                contextFor(temporaryFolder.newFolder("generated")));

        X509Certificate certificate = provider.getClientCertificate();
        byte[] pem = provider.getPemEncodedClientCertificate();
        String pemText = new String(pem, StandardCharsets.US_ASCII);
        assertTrue(pemText.startsWith("-----BEGIN CERTIFICATE-----\n"));
        assertTrue(pemText.endsWith("-----END CERTIFICATE-----\n"));
        assertFalse("The host requires Unix PEM line endings", pemText.contains("\r"));

        // Validate the exported formats through JCA, independently of the provider's parsing code.
        X509Certificate parsedCertificate = (X509Certificate) CertificateFactory
                .getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem));
        assertArrayEquals(certificate.getEncoded(), parsedCertificate.getEncoded());
        parsedCertificate.checkValidity();
        parsedCertificate.verify(parsedCertificate.getPublicKey());

        PrivateKey privateKey = provider.getClientPrivateKey();
        assertEquals("PKCS#8", privateKey.getFormat());
        PrivateKey parsedKey = KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(privateKey.getEncoded()));
        assertCanSignFor(parsedCertificate, parsedKey);
    }

    @Test
    public void reopeningCredentialsPreservesPairingIdentity() throws Exception {
        File directory = temporaryFolder.newFolder("persisted");
        Context context = contextFor(directory);
        AndroidCryptoProvider original = new AndroidCryptoProvider(context);
        X509Certificate originalCertificate = original.getClientCertificate();
        byte[] originalPem = original.getPemEncodedClientCertificate();
        byte[] originalKey = original.getClientPrivateKey().getEncoded();
        File certificateFile = new File(directory, "client.crt");
        File keyFile = new File(directory, "client.key");
        assertArrayEquals(originalPem, Files.readAllBytes(certificateFile.toPath()));
        assertArrayEquals(originalKey, Files.readAllBytes(keyFile.toPath()));

        AndroidCryptoProvider reopened = new AndroidCryptoProvider(context);
        // Exercise the private-key-first loading path as well as certificate-first generation.
        PrivateKey reloadedKey = reopened.getClientPrivateKey();
        assertArrayEquals(originalKey, reloadedKey.getEncoded());
        assertArrayEquals(originalCertificate.getEncoded(),
                reopened.getClientCertificate().getEncoded());
        assertArrayEquals(originalPem, reopened.getPemEncodedClientCertificate());
        assertArrayEquals(originalPem, Files.readAllBytes(certificateFile.toPath()));
        assertArrayEquals(originalKey, Files.readAllBytes(keyFile.toPath()));
        assertCanSignFor(originalCertificate, reloadedKey);
    }

    private static void assertCanSignFor(X509Certificate certificate, PrivateKey key)
            throws Exception {
        byte[] challenge = "Moonlight pairing challenge".getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(challenge);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(challenge);
        assertTrue(verifier.verify(signature));

        challenge[0] ^= 1;
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(challenge);
        assertFalse(verifier.verify(signature));
    }
}
