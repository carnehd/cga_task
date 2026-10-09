package pt.cga.sjiam.keycloak;

import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import pt.cga.sjiam.config.SjIamProperties;

// APENAS TESTES LOCAIS: cliente de demonstração da Admin API. O sj-iam real já faz as chamadas de
// associação a grupos; o que migra é o padrão withToken() (Bearer + renovação após 401), marcado abaixo.

/**
 * Chamadas à Admin API do Keycloak autenticadas com o token da service account.
 * As roles manage-users, query-users e view-users (tarefa 2) cobrem tudo o que aqui se faz.
 */
@Component
public class KeycloakAdminClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);
    private static final ParameterizedTypeReference<List<KeycloakUser>> USER_LIST = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<KeycloakGroup>> GROUP_LIST = new ParameterizedTypeReference<>() { };

    private final RestClient restClient;
    private final ServiceAccountTokens tokens;
    private final String adminRealmUrl;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeycloakUser(String id, String username) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeycloakGroup(String id, String name, String path) {
    }

    public KeycloakAdminClient(RestClient.Builder restClientBuilder, ServiceAccountTokens tokens,
            SjIamProperties properties) {
        this.restClient = restClientBuilder.build();
        this.tokens = tokens;
        this.adminRealmUrl = properties.keycloak().adminRealmUrl();
    }

    public KeycloakUser findUser(String username) {
        List<KeycloakUser> users = withToken(token -> restClient.get()
                .uri(adminRealmUrl + "/users?username={username}&exact=true", username)
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(USER_LIST));
        return users.stream()
                .filter(u -> username.equalsIgnoreCase(u.username()))
                .findFirst()
                .orElseThrow(() -> new KeycloakResourceNotFoundException("Utilizador não encontrado no Keycloak: " + username));
    }

    public KeycloakGroup findGroup(String groupName) {
        List<KeycloakGroup> groups = withToken(token -> restClient.get()
                .uri(adminRealmUrl + "/groups?search={name}&exact=true", groupName)
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(GROUP_LIST));
        return groups.stream()
                .filter(g -> groupName.equals(g.name()))
                .findFirst()
                .orElseThrow(() -> new KeycloakResourceNotFoundException("Grupo não encontrado no Keycloak: " + groupName));
    }

    /** Associa o utilizador ao grupo (idempotente no Keycloak). */
    public void addUserToGroup(String username, String groupName) {
        KeycloakUser user = findUser(username);
        KeycloakGroup group = findGroup(groupName);
        withToken(token -> restClient.put()
                .uri(adminRealmUrl + "/users/{userId}/groups/{groupId}", user.id(), group.id())
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .toBodilessEntity());
        log.info("Utilizador '{}' associado ao grupo '{}'", username, group.path());
    }

    public List<KeycloakGroup> groupsOf(String username) {
        KeycloakUser user = findUser(username);
        return withToken(token -> restClient.get()
                .uri(adminRealmUrl + "/users/{userId}/groups", user.id())
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(GROUP_LIST));
    }

    //USAR CODIGO: aplicar este padrão às chamadas existentes à Admin API do Keycloak no sj-iam real.
    /** Executa a chamada com o token atual; num 401 descarta o token e tenta uma vez mais. */
    private <T> T withToken(Function<String, T> call) {
        try {
            return call.apply(tokens.accessToken());
        } catch (HttpClientErrorException.Unauthorized e) {
            log.info("Admin API respondeu 401; a renovar o token da service account");
            tokens.invalidate();
            return call.apply(tokens.accessToken());
        }
    }
}
