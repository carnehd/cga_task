package pt.cga.sjiam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// APENAS TESTES LOCAIS: o sj-iam real já tem a sua classe de arranque.
// Garantir apenas que SjIamProperties fica registada (@ConfigurationPropertiesScan ou @EnableConfigurationProperties).

// Sem utilizadores locais: a API não usa autenticação por utilizador/password, por isso
// desliga-se o UserDetailsService por omissão (e o aviso de password gerada no arranque).
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class SjIamApplication {

    public static void main(String[] args) {
        SpringApplication.run(SjIamApplication.class, args);
    }
}
