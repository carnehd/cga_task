package pt.cga.sjiam.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import pt.cga.sjiam.TestFixtures;

// APENAS TESTES LOCAIS: testa o cliente de demonstração. O teste renewsTheTokenOnceAfterA401
// é reaproveitável se o padrão withToken() for migrado.

/** Admin API com o token da service account, incluindo renovação após 401. */
class KeycloakAdminClientTest {

    private static final String USERS_URL = TestFixtures.ADMIN_URL + "/users?username=alice&exact=true";
    private static final String GROUPS_URL = TestFixtures.ADMIN_URL + "/groups?search=sj-users&exact=true";

    /** Stub de tokens: devolve tok-1, e tok-2 depois de invalidate(). */
    private static final class StubTokens implements ServiceAccountTokens {
        private int generation = 1;

        @Override
        public String accessToken() {
            return "tok-" + generation;
        }

        @Override
        public void invalidate() {
            generation++;
        }
    }

    private final StubTokens tokens = new StubTokens();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final KeycloakAdminClient client = new KeycloakAdminClient(builder, tokens, TestFixtures.properties());

    @Test
    void addsTheUserToTheGroupWithABearerToken() {
        server.expect(once(), requestTo(USERS_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-1"))
                .andRespond(withSuccess("[{\"id\":\"u1\",\"username\":\"alice\",\"enabled\":true}]", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(GROUPS_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-1"))
                .andRespond(withSuccess("[{\"id\":\"g1\",\"name\":\"sj-users\",\"path\":\"/sj-users\",\"subGroupCount\":0}]", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TestFixtures.ADMIN_URL + "/users/u1/groups/g1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-1"))
                .andRespond(withNoContent());

        client.addUserToGroup("alice", "sj-users");
        server.verify();
    }

    @Test
    void renewsTheTokenOnceAfterA401() {
        server.expect(once(), requestTo(USERS_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(once(), requestTo(USERS_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-2"))
                .andRespond(withSuccess("[{\"id\":\"u1\",\"username\":\"alice\"}]", MediaType.APPLICATION_JSON));

        assertEquals("u1", client.findUser("alice").id());
        server.verify();
    }

    @Test
    void reportsUnknownUsersAsNotFound() {
        server.expect(once(), requestTo(USERS_URL))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThrows(KeycloakResourceNotFoundException.class, () -> client.findUser("alice"));
        server.verify();
    }

    @Test
    void listsTheGroupsOfAUser() {
        server.expect(once(), requestTo(USERS_URL))
                .andRespond(withSuccess("[{\"id\":\"u1\",\"username\":\"alice\"}]", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TestFixtures.ADMIN_URL + "/users/u1/groups"))
                .andRespond(withSuccess("[{\"id\":\"g1\",\"name\":\"sj-users\",\"path\":\"/sj-users\"}]", MediaType.APPLICATION_JSON));

        List<KeycloakAdminClient.KeycloakGroup> groups = client.groupsOf("alice");
        assertEquals(List.of("/sj-users"), groups.stream().map(KeycloakAdminClient.KeycloakGroup::path).toList());
        server.verify();
    }
}
