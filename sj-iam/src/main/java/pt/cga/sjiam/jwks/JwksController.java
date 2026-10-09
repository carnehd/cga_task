package pt.cga.sjiam.jwks;

import java.time.Duration;
import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import pt.cga.sjiam.config.SecurityConfig;
import pt.cga.sjiam.keys.SigningKeyProvider;

//USAR CODIGO: endpoint GET /.well-known/jwks.json (tarefa 1).

/**
 * Tarefa 1: publica a chave pública do sj-iam em formato JWK Set (RFC 7517),
 * para o Keycloak validar as client assertions assinadas pelo sj-iam.
 */
@RestController
public class JwksController {

    private final SigningKeyProvider keys;

    public JwksController(SigningKeyProvider keys) {
        this.keys = keys;
    }

    @GetMapping(value = SecurityConfig.JWKS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        // toJSONObject() serializa apenas a parte pública, mesmo que a chave tivesse parte privada.
        JWKSet jwkSet = new JWKSet(keys.publicKey());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(jwkSet.toJSONObject());
    }
}
