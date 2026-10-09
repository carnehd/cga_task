package pt.cga.sjiam.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import pt.cga.sjiam.keycloak.KeycloakAdminClient;
import pt.cga.sjiam.keycloak.KeycloakAdminClient.KeycloakGroup;

// APENAS TESTES LOCAIS: endpoint de demonstração para exercitar a autenticação da service account.
// O sj-iam real já tem a sua própria lógica de associação de utilizadores a grupos.

/**
 * API de demonstração que consome a autenticação da tarefa 3: associa utilizadores a
 * grupos no Keycloak através da service account.
 */
@RestController
@RequestMapping("/api/users/{username}/groups")
public class UserGroupController {

    private final KeycloakAdminClient keycloak;

    public UserGroupController(KeycloakAdminClient keycloak) {
        this.keycloak = keycloak;
    }

    @PutMapping("/{groupName}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addToGroup(@PathVariable String username, @PathVariable String groupName) {
        keycloak.addUserToGroup(username, groupName);
    }

    @GetMapping
    public List<String> groups(@PathVariable String username) {
        return keycloak.groupsOf(username).stream().map(KeycloakGroup::path).toList();
    }
}
