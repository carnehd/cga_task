package pt.cga.sjiam.keycloak;

//USAR CODIGO

/** Fornece o access token da service account do sj-iam no Keycloak. */
public interface ServiceAccountTokens {

    /** Access token válido (renovado automaticamente perto da expiração). */
    String accessToken();

    /** Descarta o token em cache; o próximo {@link #accessToken()} pede um novo. */
    void invalidate();
}
