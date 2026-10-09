package pt.cga.sjiam.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

//USAR CODIGO: resposta do token endpoint do Keycloak.

/** Resposta do token endpoint do Keycloak (apenas os campos usados). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("token_type") String tokenType) {
}
