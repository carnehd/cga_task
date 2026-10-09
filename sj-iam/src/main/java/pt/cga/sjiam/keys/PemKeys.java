package pt.cga.sjiam.keys;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

//USAR CODIGO: parser da chave privada PEM (PKCS#1 e PKCS#8) e derivação da chave pública.

/**
 * Converte uma chave privada RSA em PEM (como a produzida pelo recurso Terraform
 * {@code tls_private_key} ou pelo openssl) em objetos JCA.
 *
 * <p>Aceita PKCS#8 ({@code BEGIN PRIVATE KEY}) e PKCS#1 ({@code BEGIN RSA PRIVATE KEY}).
 * A chave pública é derivada dos parâmetros CRT da privada, por isso não é preciso
 * guardar a chave pública nem um certificado no Secret Manager.
 */
public final class PemKeys {

    private static final String PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PKCS1_BEGIN = "-----BEGIN RSA PRIVATE KEY-----";
    private static final String ENCRYPTED_BEGIN = "-----BEGIN ENCRYPTED PRIVATE KEY-----";

    /** DER de {@code AlgorithmIdentifier { rsaEncryption (1.2.840.113549.1.1.1), NULL }}. */
    private static final byte[] RSA_ALGORITHM_IDENTIFIER = {
            0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
    };

    public record RsaKeyPair(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
    }

    private PemKeys() {
    }

    public static RsaKeyPair parsePrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("Chave privada PEM vazia");
        }
        byte[] pkcs8;
        if (pem.contains(PKCS8_BEGIN)) {
            pkcs8 = decodeBlock(pem, "PRIVATE KEY");
        } else if (pem.contains(PKCS1_BEGIN)) {
            pkcs8 = wrapPkcs1InPkcs8(decodeBlock(pem, "RSA PRIVATE KEY"));
        } else if (pem.contains(ENCRYPTED_BEGIN)) {
            throw new IllegalArgumentException(
                    "Chaves privadas cifradas (BEGIN ENCRYPTED PRIVATE KEY) não são suportadas");
        } else {
            throw new IllegalArgumentException(
                    "Formato PEM não reconhecido: esperado PKCS#8 (BEGIN PRIVATE KEY) ou PKCS#1 (BEGIN RSA PRIVATE KEY)");
        }
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
                throw new IllegalArgumentException(
                        "A chave privada RSA não tem parâmetros CRT; não é possível derivar a chave pública");
            }
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(
                    new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
            return new RsaKeyPair(publicKey, crtKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Chave privada RSA inválida", e);
        }
    }

    private static byte[] decodeBlock(String pem, String label) {
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        int start = pem.indexOf(begin);
        int stop = start < 0 ? -1 : pem.indexOf(end, start);
        if (start < 0 || stop < 0) {
            throw new IllegalArgumentException("Bloco PEM '" + label + "' incompleto");
        }
        String base64 = pem.substring(start + begin.length(), stop).replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Base64 inválido no bloco PEM '" + label + "'", e);
        }
    }

    /**
     * Embrulha um {@code RSAPrivateKey} PKCS#1 num {@code PrivateKeyInfo} PKCS#8:
     * {@code SEQUENCE { INTEGER 0, AlgorithmIdentifier, OCTET STRING pkcs1 }}.
     */
    static byte[] wrapPkcs1InPkcs8(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] octetString = der(0x04, pkcs1);
        byte[] body = new byte[version.length + RSA_ALGORITHM_IDENTIFIER.length + octetString.length];
        System.arraycopy(version, 0, body, 0, version.length);
        System.arraycopy(RSA_ALGORITHM_IDENTIFIER, 0, body, version.length, RSA_ALGORITHM_IDENTIFIER.length);
        System.arraycopy(octetString, 0, body, version.length + RSA_ALGORITHM_IDENTIFIER.length, octetString.length);
        return der(0x30, body);
    }

    private static byte[] der(int tag, byte[] content) {
        byte[] length = derLength(content.length);
        byte[] out = new byte[1 + length.length + content.length];
        out[0] = (byte) tag;
        System.arraycopy(length, 0, out, 1, length.length);
        System.arraycopy(content, 0, out, 1 + length.length, content.length);
        return out;
    }

    private static byte[] derLength(int n) {
        if (n < 0x80) {
            return new byte[] {(byte) n};
        }
        int byteCount = (Integer.SIZE - Integer.numberOfLeadingZeros(n) + 7) / 8;
        byte[] out = new byte[1 + byteCount];
        out[0] = (byte) (0x80 | byteCount);
        for (int i = 0; i < byteCount; i++) {
            out[1 + i] = (byte) (n >>> (8 * (byteCount - 1 - i)));
        }
        return out;
    }
}
