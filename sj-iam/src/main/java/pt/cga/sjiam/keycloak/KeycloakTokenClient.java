package pt.cga.sjiam.keycloak;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import pt.cga.sjiam.config.SjIamProperties;

//USAR CODIGO: pedido do token client_credentials + client assertion, com cache e renovação (tarefa 3).

/**
 * Tarefa 3: obtém o access token da service account com
 * {@code grant_type=client_credentials} autenticado por client assertion, e mantém-no
 * em cache até perto da expiração. O Keycloak não devolve refresh token neste grant.
 */
@Component
public class KeycloakTokenClient implements ServiceAccountTokens {

    public static final String CLIENT_ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private static final Logger log = LoggerFactory.getLogger(KeycloakTokenClient.class);

    private final RestClient restClient;
    private final ClientAssertionFactory assertions;
    private final String clientId;
    private final String tokenEndpoint;
    private final Duration refreshSkew;
    private final Clock clock;

    private volatile CachedToken cached;

    public KeycloakTokenClient(RestClient.Builder restClientBuilder, ClientAssertionFactory assertions,
            SjIamProperties properties, Clock clock) {
        this.restClient = restClientBuilder.build();
        this.assertions = assertions;
        this.clientId = properties.keycloak().clientId();
        this.tokenEndpoint = properties.keycloak().tokenEndpoint();
        this.refreshSkew = properties.keycloak().tokenRefreshSkew();
        this.clock = clock;
    }

    @Override
    public String accessToken() {
        CachedToken token = cached;
        if (token != null && token.usableAt(clock.instant(), refreshSkew)) {
            return token.value();
        }
        synchronized (this) {
            token = cached;
            if (token != null && token.usableAt(clock.instant(), refreshSkew)) {
                return token.value();
            }
            token = requestToken();
            cached = token;
            return token.value();
        }
    }

    @Override
    public void invalidate() {
        cached = null;
    }

    private CachedToken requestToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_assertion_type", CLIENT_ASSERTION_TYPE);
        form.add("client_assertion", assertions.create());
        try {
            TokenResponse response = restClient.post()
                    .uri(tokenEndpoint)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                throw new KeycloakAuthenticationException("O token endpoint não devolveu access_token");
            }
            Instant expiresAt = clock.instant().plusSeconds(response.expiresIn());
            log.info("Access token da service account '{}' obtido; expira em {}s", clientId, response.expiresIn());
            return new CachedToken(response.accessToken(), expiresAt);
        } catch (RestClientResponseException e) {
            throw new KeycloakAuthenticationException(
                    "O Keycloak recusou a autenticação da service account '" + clientId + "' (HTTP "
                            + e.getStatusCode().value() + "): " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new KeycloakAuthenticationException("Não foi possível contactar o token endpoint " + tokenEndpoint, e);
        }
    }

    private record CachedToken(String value, Instant expiresAt) {

        boolean usableAt(Instant now, Duration skew) {
            return now.plus(skew).isBefore(expiresAt);
        }
    }
}
