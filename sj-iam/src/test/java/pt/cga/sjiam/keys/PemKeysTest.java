package pt.cga.sjiam.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.Signature;

import org.junit.jupiter.api.Test;

//USAR CODIGO: migrar com a classe testada (usa os PEM de teste em src/test/resources/keys).

class PemKeysTest {

    @Test
    void parsesPkcs8AndPkcs1IntoTheSameKey() throws Exception {
        PemKeys.RsaKeyPair pkcs8 = PemKeys.parsePrivateKey(resource("keys/test-key-pkcs8.pem"));
        PemKeys.RsaKeyPair pkcs1 = PemKeys.parsePrivateKey(resource("keys/test-key-pkcs1.pem"));

        assertEquals(3072, pkcs8.publicKey().getModulus().bitLength());
        assertEquals(BigInteger.valueOf(65537), pkcs8.publicKey().getPublicExponent());
        assertEquals(pkcs8.publicKey().getModulus(), pkcs1.publicKey().getModulus());
        assertEquals(pkcs8.privateKey().getPrivateExponent(), pkcs1.privateKey().getPrivateExponent());
    }

    @Test
    void derivedPublicKeyVerifiesSignaturesMadeWithThePrivateKey() throws Exception {
        PemKeys.RsaKeyPair pair = PemKeys.parsePrivateKey(resource("keys/test-key-pkcs1.pem"));
        byte[] payload = "client assertion".getBytes(StandardCharsets.UTF_8);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.privateKey());
        signer.update(payload);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.publicKey());
        verifier.update(payload);
        assertTrue(verifier.verify(signature));
    }

    @Test
    void toleratesSurroundingWhitespaceAndWindowsLineEndings() throws Exception {
        String pem = resource("keys/test-key-pkcs8.pem");
        PemKeys.RsaKeyPair pair = PemKeys.parsePrivateKey("\n  " + pem.replace("\n", "\r\n") + "\r\n\r\n");
        assertEquals(3072, pair.publicKey().getModulus().bitLength());
    }

    @Test
    void rejectsUnknownPemBlocks() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PemKeys.parsePrivateKey("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----"));
        assertTrue(e.getMessage().contains("Formato PEM não reconhecido"));
    }

    @Test
    void rejectsEncryptedKeys() {
        assertThrows(IllegalArgumentException.class, () -> PemKeys.parsePrivateKey(
                "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----"));
    }

    @Test
    void rejectsEmptyInput() {
        assertThrows(IllegalArgumentException.class, () -> PemKeys.parsePrivateKey("   "));
    }

    private String resource(String path) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Recurso de teste em falta: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
