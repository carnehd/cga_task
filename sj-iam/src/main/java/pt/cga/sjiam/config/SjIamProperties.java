package pt.cga.sjiam.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

//USAR CODIGO: configuração dos secrets e do Keycloak; migrar (ou fundir com as properties já existentes no sj-iam real).

/**
 * Configuração do sj-iam (prefixo {@code sjiam} no application.yml).
 */
@ConfigurationProperties(prefix = "sjiam")
public record SjIamProperties(Secrets secrets, Keycloak keycloak) {

    /**
     * De onde vêm os secrets com a chave privada e o kid.
     *
     * @param provider      {@code file} (local) ou {@code gcp} (Google Secret Manager)
     * @param keySecretName nome do secret com a chave privada RSA em PEM
     * @param kidSecretName nome do secret com o key id (iamsj-YYYY-MM)
     */
    public record Secrets(
            @DefaultValue("file") String provider,
            @DefaultValue("iamsj_client_auth") String keySecretName,
            @DefaultValue("iamsj_client_auth_kid") String kidSecretName,
            @DefaultValue FileSecrets file,
            @DefaultValue GcpSecrets gcp) {

        /** Provider local: um ficheiro por secret, dentro de {@code directory}. */
        public record FileSecrets(@DefaultValue("./secrets") String directory) {
        }

        /** Provider GCP: secrets em {@code projects/<projectId>/secrets/<nome>/versions/latest}. */
        public record GcpSecrets(String projectId) {
        }
    }

    /**
     * Keycloak onde vive o cliente {@code iamsj} (tarefa 2).
     *
     * @param baseUrl          URL base do Keycloak, sem o caminho do realm
     * @param realm            nome do realm
     * @param clientId         client id da service account
     * @param assertionTtl     validade da client assertion (RFC 7523)
     * @param tokenRefreshSkew antecedência com que o access token é renovado
     */
    public record Keycloak(
            String baseUrl,
            String realm,
            @DefaultValue("iamsj") String clientId,
            @DefaultValue("60s") Duration assertionTtl,
            @DefaultValue("30s") Duration tokenRefreshSkew) {

        public String issuerUrl() {
            return trimTrailingSlash(baseUrl) + "/realms/" + realm;
        }

        /** Token endpoint: destino do pedido de token e audience da client assertion. */
        public String tokenEndpoint() {
            return issuerUrl() + "/protocol/openid-connect/token";
        }

        public String adminRealmUrl() {
            return trimTrailingSlash(baseUrl) + "/admin/realms/" + realm;
        }

        private static String trimTrailingSlash(String url) {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
    }
}
