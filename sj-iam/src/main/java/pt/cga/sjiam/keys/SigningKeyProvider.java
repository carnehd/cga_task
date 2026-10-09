package pt.cga.sjiam.keys;

import java.util.regex.Pattern;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import pt.cga.sjiam.config.SjIamProperties;
import pt.cga.sjiam.secrets.SecretProvider;

//USAR CODIGO: carrega a chave e o kid dos secrets; partilhado pelo JWKS (tarefa 1) e pela client assertion (tarefa 3).

/**
 * Chave RSA do sj-iam, carregada uma vez no arranque a partir dos secrets
 * {@code iamsj_client_auth} (PEM) e {@code iamsj_client_auth_kid} (kid).
 *
 * <p>É usada em dois sítios: a parte pública vai para o JWKS (tarefa 1) e a parte
 * privada assina a client assertion para o Keycloak (tarefa 3). O kid tem de ser o
 * mesmo nos dois, senão o Keycloak não encontra a chave.
 */
@Component
public class SigningKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyProvider.class);
    private static final Pattern KID_FORMAT = Pattern.compile("iamsj-\\d{4}-\\d{2}");

    private final RSAKey rsaKey;

    public SigningKeyProvider(SecretProvider secrets, SjIamProperties properties) {
        SjIamProperties.Secrets names = properties.secrets();
        String kid = secrets.get(names.kidSecretName());
        if (kid.isBlank()) {
            throw new IllegalStateException("O secret " + names.kidSecretName() + " está vazio");
        }
        if (!KID_FORMAT.matcher(kid).matches()) {
            log.warn("kid '{}' não segue o formato esperado iamsj-YYYY-MM", kid);
        }
        PemKeys.RsaKeyPair pair = PemKeys.parsePrivateKey(secrets.get(names.keySecretName()));
        this.rsaKey = new RSAKey.Builder(pair.publicKey())
                .privateKey(pair.privateKey())
                .keyID(kid)
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
        log.info("Chave de assinatura carregada: kid={}, RSA {} bits", kid, pair.publicKey().getModulus().bitLength());
    }

    /** Chave completa (com parte privada), só para assinar. Nunca serializar. */
    public RSAKey signingKey() {
        return rsaKey;
    }

    /** Só a parte pública, para publicar no JWKS. */
    public RSAKey publicKey() {
        return rsaKey.toPublicJWK();
    }
}
