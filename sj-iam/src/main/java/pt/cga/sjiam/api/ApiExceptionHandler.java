package pt.cga.sjiam.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;

import pt.cga.sjiam.keycloak.KeycloakAuthenticationException;
import pt.cga.sjiam.keycloak.KeycloakResourceNotFoundException;

// APENAS TESTES LOCAIS, exceto o handler marcado com USAR CODIGO.

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(KeycloakResourceNotFoundException.class)
    ProblemDetail notFound(KeycloakResourceNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    //USAR CODIGO (opcional): mapear KeycloakAuthenticationException para 502 no handler de erros do sj-iam real.
    @ExceptionHandler(KeycloakAuthenticationException.class)
    ProblemDetail authenticationFailed(KeycloakAuthenticationException e) {
        log.error("Falha na autenticação da service account no Keycloak", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(RestClientResponseException.class)
    ProblemDetail upstreamError(RestClientResponseException e) {
        log.error("Erro da Admin API do Keycloak: HTTP {} {}", e.getStatusCode().value(), e.getResponseBodyAsString());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "A Admin API do Keycloak respondeu HTTP " + e.getStatusCode().value());
    }
}
