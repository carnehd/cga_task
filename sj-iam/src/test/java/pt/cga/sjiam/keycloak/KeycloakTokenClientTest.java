package pt.cga.sjiam.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import pt.cga.sjiam.MutableClock;
import pt.cga.sjiam.TestFixtures;
import pt.cga.sjiam.keys.SigningKeyProvider;

//USAR CODIGO: migrar com a classe testada.

/** Tarefa 3: pedido de token com private_key_jwt, cache e renovação. */
class KeycloakTokenClientTest {

    private static final String TOKEN_JSON =
            "{\"access_token\":\"%s\",\"expires_in\":300,\"refresh_expires_in\":0,\"token_type\":\"Bearer\",\"scope\":\"\"}";

    private final MutableClock clock = new MutableClock(TestFixtures.NOW);
    private final SigningKeyProvider keys = TestFixtures.signingKeyProvider();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final KeycloakTokenClient client = new KeycloakTokenClient(builder,
            new ClientAssertionFactory(keys, TestFixtures.properties(), clock), TestFixtures.properties(), clock);

    @Test
    void requestsTheTokenWithAClientAssertionAndCachesIt() throws Exception {
        server.expect(once(), requestTo(TestFixtures.TOKEN_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(request -> {
                    Map<String, String> form = formParams((MockClientHttpRequest) request);
                    assertEquals("client_credentials", form.get("grant_type"));
                    assertEquals(TestFixtures.CLIENT_ID, form.get("client_id"));
                    assertEquals(KeycloakTokenClient.CLIENT_ASSERTION_TYPE, form.get("client_assertion_type"));
                    SignedJWT assertion = verifiedAssertion(form.get("client_assertion"));
                    assertEquals(TestFixtures.KID, assertion.getHeader().getKeyID());
                })
                .andRespond(withSuccess(TOKEN_JSON.formatted("tok-1"), MediaType.APPLICATION_JSON));

        assertEquals("tok-1", client.accessToken());
        assertEquals("tok-1", client.accessToken());
        server.verify();
    }

    @Test
    void renewsTheTokenBeforeItExpires() {
        server.expect(once(), requestTo(TestFixtures.TOKEN_ENDPOINT))
                .andRespond(withSuccess(TOKEN_JSON.formatted("tok-1"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TestFixtures.TOKEN_ENDPOINT))
                .andRespond(withSuccess(TOKEN_JSON.formatted("tok-2"), MediaType.APPLICATION_JSON));

        assertEquals("tok-1", client.accessToken());
        clock.advance(Duration.ofSeconds(269));
        assertEquals("tok-1", client.accessToken(), "ainda fora da janela de renovação (300s - 30s)");
        clock.advance(Duration.ofSeconds(2));
        assertEquals("tok-2", client.accessToken(), "renovado 30s antes de expirar");
        server.verify();
    }

    @Test
    void invalidateForcesANewToken() {
        server.expect(once(), requestTo(TestFixtures.TOKEN_ENDPOINT))
                .andRespond(withSuccess(TOKEN_JSON.formatted("tok-1"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TestFixtures.TOKEN_ENDPOINT))
                .andRespond(withSuccess(TOKEN_JSON.formatted("tok-2"), MediaType.APPLICATION_JSON));

        assertEquals("tok-1", client.accessToken());
        client.invalidate();
        assertEquals("tok-2", client.accessToken());
        server.verify();
    }

    @Test
    void surfacesKeycloakErrorsWithTheirDescription() {
        server.expect(once(), requestTo(TestFixtures.TOKEN_ENDPOINT))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\",\"error_description\":\"Client authentication with signed JWT failed\"}"));

        KeycloakAuthenticationException e = assertThrows(KeycloakAuthenticationException.class, client::accessToken);
        assertTrue(e.getMessage().contains("HTTP 401"));
        assertTrue(e.getMessage().contains("invalid_client"));
        server.verify();
    }

    private SignedJWT verifiedAssertion(String compactJws) {
        try {
            SignedJWT jwt = SignedJWT.parse(compactJws);
            assertTrue(jwt.verify(new RSASSAVerifier(keys.publicKey())), "assinatura da client assertion inválida");
            return jwt;
        } catch (ParseException | JOSEException e) {
            throw new AssertionError("client assertion inválida", e);
        }
    }

    private static Map<String, String> formParams(MockClientHttpRequest request) {
        return Arrays.stream(request.getBodyAsString().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        kv -> URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv -> URLDecoder.decode(kv[1], StandardCharsets.UTF_8)));
    }
}
