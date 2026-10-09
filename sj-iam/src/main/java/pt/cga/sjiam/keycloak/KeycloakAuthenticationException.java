package pt.cga.sjiam.keycloak;

//USAR CODIGO

/** Falha a obter o access token da service account no token endpoint do Keycloak. */
public class KeycloakAuthenticationException extends RuntimeException {

    public KeycloakAuthenticationException(String message) {
        super(message);
    }

    public KeycloakAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
