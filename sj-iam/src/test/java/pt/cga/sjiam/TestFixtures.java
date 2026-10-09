package pt.cga.sjiam;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import pt.cga.sjiam.config.SjIamProperties;
import pt.cga.sjiam.keys.SigningKeyProvider;
import pt.cga.sjiam.secrets.FileSecretProvider;

//USAR CODIGO: suporte dos testes migrados (usa src/test/resources/secrets).

public final class TestFixtures {

    public static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    public static final String KID = "iamsj-2026-10";
    public static final String CLIENT_ID = "iamsj";
    public static final String TOKEN_ENDPOINT = "http://keycloak.test:8080/realms/CGA/protocol/openid-connect/token";
    public static final String ADMIN_URL = "http://keycloak.test:8080/admin/realms/CGA";

    private TestFixtures() {
    }

    public static SjIamProperties properties() {
        return new SjIamProperties(
                new SjIamProperties.Secrets("file", "iamsj_client_auth", "iamsj_client_auth_kid",
                        new SjIamProperties.Secrets.FileSecrets("src/test/resources/secrets"),
                        new SjIamProperties.Secrets.GcpSecrets(null)),
                new SjIamProperties.Keycloak("http://keycloak.test:8080", "CGA", CLIENT_ID,
                        Duration.ofSeconds(60), Duration.ofSeconds(30)));
    }

    public static SigningKeyProvider signingKeyProvider() {
        return new SigningKeyProvider(new FileSecretProvider(properties()), properties());
    }

    public static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
