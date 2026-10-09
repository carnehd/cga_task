package pt.cga.sjiam.keycloak;

//USAR CODIGO (se o sj-iam real não tiver já uma exceção equivalente).

/** Utilizador ou grupo inexistente no realm do Keycloak. */
public class KeycloakResourceNotFoundException extends RuntimeException {

    public KeycloakResourceNotFoundException(String message) {
        super(message);
    }
}
