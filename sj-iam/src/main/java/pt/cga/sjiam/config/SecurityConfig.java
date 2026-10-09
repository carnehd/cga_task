package pt.cga.sjiam.config;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

// APENAS TESTES LOCAIS, exceto a constante JWKS_PATH e a regra permitAll do JWKS marcadas com USAR CODIGO.

@Configuration
public class SecurityConfig {

    //USAR CODIGO: caminho partilhado entre o controller e a regra de segurança.
    public static final String JWKS_PATH = "/.well-known/jwks.json";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // Tarefa 1: o Keycloak lê o JWKS sem credenciais.
                        //USAR CODIGO: o Keycloak lê o JWKS sem credenciais; esta regra tem de existir no SecurityConfig do sj-iam real.
                        .requestMatchers(HttpMethod.GET, JWKS_PATH).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // API interna de demonstração (associação a grupos). Aberta apenas neste
                        // scaffold local: no sj-iam real tem de ficar atrás do Apigee e/ou de um
                        // resource server que valide tokens do Keycloak.
                        .requestMatchers("/api/**").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
