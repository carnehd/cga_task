package pt.cga.sjiam.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

//USAR CODIGO: bean Clock usado pelo ClientAssertionFactory e pelo KeycloakTokenClient (permite testar iat/exp e a cache do token).

@Configuration
public class AppConfig {

    /** Relógio injetável, para os testes controlarem iat/exp e a cache do token. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
