package pt.cga.sjiam.keycloak;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.springframework.stereotype.Component;

import pt.cga.sjiam.config.SjIamProperties;
import pt.cga.sjiam.keys.SigningKeyProvider;

//USAR CODIGO: client assertion RFC 7523 / private_key_jwt (tarefa 3).

/**
 * Tarefa 3: constrói a client assertion (RFC 7523 / private_key_jwt) com que o sj-iam
 * se autentica no token endpoint do Keycloak.
 *
 * <ul>
 *   <li>{@code iss} e {@code sub}: o client id ({@code iamsj});</li>
 *   <li>{@code aud}: o token endpoint do realm;</li>
 *   <li>{@code jti}: único por pedido (o Keycloak rejeita reutilizações);</li>
 *   <li>{@code iat}/{@code exp}: validade curta;</li>
 *   <li>cabeçalho {@code kid}: o mesmo kid publicado no JWKS, para o Keycloak escolher a chave.</li>
 * </ul>
 */
@Component
public class ClientAssertionFactory {

    private final RSAKey signingKey;
    private final RSASSASigner signer;
    private final String clientId;
    private final String audience;
    private final Duration ttl;
    private final Clock clock;

    public ClientAssertionFactory(SigningKeyProvider keys, SjIamProperties properties, Clock clock) {
        this.signingKey = keys.signingKey();
        try {
            this.signer = new RSASSASigner(signingKey);
        } catch (JOSEException e) {
            throw new IllegalStateException("Chave de assinatura RSA inválida", e);
        }
        SjIamProperties.Keycloak keycloak = properties.keycloak();
        this.clientId = keycloak.clientId();
        this.audience = keycloak.tokenEndpoint();
        this.ttl = keycloak.assertionTtl();
        this.clock = clock;
    }

    /** Nova assertion assinada, serializada em compact JWS. */
    public String create() {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(clientId)
                .subject(clientId)
                .audience(audience)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(signingKey.getKeyID())
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Falha ao assinar a client assertion", e);
        }
        return jwt.serialize();
    }
}
