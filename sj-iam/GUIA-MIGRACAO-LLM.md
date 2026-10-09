# Guia de migração para o sj-iam real (destinado a uma LLM)

> Este documento é para um assistente de código (LLM) que vai implementar, no microserviço real
> `sj-iam`, o endpoint JWKS (tarefa 1) e a autenticação da service account no Keycloak por
> `private_key_jwt` (tarefa 3), usando este projeto como implementação de referência.
> Lê o documento inteiro antes de alterar código. Os caminhos indicados são relativos à pasta
> deste projeto de referência (`sj-iam/`).

---

## 0. Contexto

- O sj-iam é um serviço Spring Boot que chama a **Admin API do Keycloak** para associar
  utilizadores a grupos. Essas chamadas **já existem** no serviço real; o que falta é
  autenticá-las com a service account.
- A autenticação é **client_credentials + client assertion (RFC 7523)**: o sj-iam assina um JWT
  com a sua chave privada RSA; o Keycloak valida a assinatura indo buscar a chave pública ao
  endpoint JWKS do sj-iam; devolve um access token; o sj-iam usa-o como `Bearer` na Admin API.
- A chave RSA 3072 **já existe** no Google Secret Manager (tarefa já feita, em Terraform):
  - secret `iamsj_client_auth`: chave privada em PEM;
  - secret `iamsj_client_auth_kid`: key id no formato `iamsj-YYYY-MM` (ano e mês da geração).
- O cliente `iamsj` no Keycloak (tarefa 2) é configurado pela equipa de Keycloak, não por ti.
  Precisa do URL público (via Apigee interno) do JWKS que tu vais expor.
- Este projeto de referência corre localmente com um Keycloak em Docker e secrets em ficheiros.
  **Só o código marcado `//USAR CODIGO` é final.** O marcado `// APENAS TESTES LOCAIS` é andaime.

```bash
grep -rn "USAR CODIGO" src pom.xml          # tudo o que migra
grep -rln "APENAS TESTES LOCAIS" src scripts compose.yaml   # tudo o que não migra
```

---

## 1. Regras para a LLM

1. **Não copies ficheiros `APENAS TESTES LOCAIS`.** Em ficheiros mistos (`SecurityConfig`,
   `KeycloakAdminClient`, `ApiExceptionHandler`) migra só os blocos marcados.
2. **Responde primeiro às perguntas da secção 2** lendo o repositório real. O que não conseguires
   determinar, pergunta ao utilizador antes de escrever código. Não assumas.
3. **Preserva nomes e formatos fixados pela tarefa 0**: nomes dos secrets, formato do kid,
   algoritmo RS256. Não os tornes "configuráveis de outra forma" sem pedido explícito.
4. **Adapta, não reescreve.** Package base, estilo de configuração e cliente HTTP devem seguir os
   do serviço real; a lógica (claims, cache, parsing PEM) fica igual à de referência.
5. **Não acrescentes dependências além das listadas na secção 3.** Em particular, o parsing PEM
   não precisa de BouncyCastle.
6. **Nunca registes em log** a chave privada, a client assertion ou o access token.
7. No fim, percorre a **checklist da secção 9** e reporta o que ficou por verificar.

---

## 2. Confirmar no repositório real antes de começar

| # | Pergunta | Onde ver | Impacto |
|---|---|---|---|
| 1 | Versão do Spring Boot e do Java | `pom.xml` / `build.gradle` | A referência usa Boot 3.5 / Java 21 e `RestClient` (Boot ≥ 3.2). Com Boot mais antigo, usa `RestTemplate` ou `WebClient` nos dois clientes (secção 7). |
| 2 | Package base | pasta `src/main/java` | Substituir `pt.cga.sjiam` por o do serviço. |
| 3 | Como o serviço lê secrets hoje | config, `application*.yml`, dependências `spring-cloud-gcp` | Decide o provider (secção 4). Se já houver Spring Cloud GCP Secret Manager (`sm://`), podes ligar o `SecretProvider` a essa fonte em vez de usar `GoogleSecretManagerSecretProvider`. |
| 4 | Formato do secret `iamsj_client_auth` | consola do Secret Manager ou Terraform (`tls_private_key.private_key_pem` → PKCS#1) | `PemKeys` aceita PKCS#1 e PKCS#8. Se for um JKS em base64, substitui o parsing em `SigningKeyProvider` por `KeyStore`. |
| 5 | Já existe um `SecurityFilterChain`? | classe com `@EnableWebSecurity` / bean `SecurityFilterChain` | Acrescenta a regra do JWKS **nessa** chain. Não cries uma segunda chain concorrente. |
| 6 | Como são feitas hoje as chamadas à Admin API do Keycloak | procurar `admin/realms`, `RestTemplate`, `WebClient`, `keycloak-admin-client` | Define onde entra o token (secção 7). |
| 7 | URL base do Keycloak e realm por ambiente | config existente | Passam para `sjiam.keycloak.base-url` / `realm` ou para as propriedades já existentes. |
| 8 | Context path ou prefixo do serviço, e o caminho que o Apigee expõe | `server.servlet.context-path`, config do Apigee | O cliente Keycloak vai ser configurado com o URL final do JWKS. Entrega esse URL ao utilizador no fim. |
| 9 | Nome do client id no Keycloak | tarefa 2: `iamsj` | `sjiam.keycloak.client-id`. |

---

## 3. Dependências a adicionar

```xml
<!-- JWK / JWS (tarefas 1 e 3). Versão explícita: não é gerida pelo BOM do Spring Boot. -->
<dependency>
  <groupId>com.nimbusds</groupId>
  <artifactId>nimbus-jose-jwt</artifactId>
  <version>10.10</version>
</dependency>

<!-- Só se usares GoogleSecretManagerSecretProvider (secção 4) -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.google.cloud</groupId>
      <artifactId>libraries-bom</artifactId>
      <version>26.90.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependency>
  <groupId>com.google.cloud</groupId>
  <artifactId>google-cloud-secretmanager</artifactId>
</dependency>
```

Se o serviço já tiver `spring-security-oauth2-jose` ou `spring-security-oauth2-resource-server`,
o `nimbus-jose-jwt` já vem transitivamente; nesse caso não fixes a versão, usa a que vier.

---

## 4. Configuração

Copiar `config/SjIamProperties.java` (record `@ConfigurationProperties(prefix = "sjiam")`) e
registá-lo (`@ConfigurationPropertiesScan` ou `@EnableConfigurationProperties`). Bloco a juntar ao
`application.yml` do serviço:

```yaml
sjiam:
  secrets:
    provider: gcp                       # gcp em todos os ambientes GCP; file só se os secrets forem montados como ficheiros
    key-secret-name: iamsj_client_auth  # fixo (tarefa 0)
    kid-secret-name: iamsj_client_auth_kid
    gcp:
      project-id: ${GCP_PROJECT_ID}
  keycloak:
    base-url: ${KEYCLOAK_BASE_URL}      # sem /realms/...
    realm: ${KEYCLOAK_REALM:CGA}
    client-id: iamsj
    assertion-ttl: 60s
    token-refresh-skew: 30s
```

Requisitos no GCP: o workload tem Application Default Credentials e a sua service account tem
`roles/secretmanager.secretAccessor` nos dois secrets. O `GoogleSecretManagerSecretProvider` lê a
versão `latest`.

Provider `file` (`FileSecretProvider`): usar apenas se os secrets forem montados como volume
(um ficheiro por secret, `sjiam.secrets.file.directory` aponta para a pasta). Caso contrário não o
migres.

---

## 5. Mapa de ficheiros (origem → destino sugerido)

| Origem neste projeto | Destino no sj-iam real | Notas |
|---|---|---|
| `src/main/java/pt/cga/sjiam/config/SjIamProperties.java` | `<base>/config/SjIamProperties.java` | ou fundir com as properties existentes, mantendo os nomes das chaves |
| `config/AppConfig.java` | onde ficam os `@Bean` de infraestrutura | só o bean `Clock`; se já existir um, reutiliza |
| `secrets/SecretProvider.java`, `secrets/SecretNotFoundException.java` | `<base>/secrets/` | interface usada por `SigningKeyProvider` |
| `secrets/GoogleSecretManagerSecretProvider.java` | `<base>/secrets/` | condicional a `sjiam.secrets.provider=gcp` |
| `secrets/FileSecretProvider.java` | `<base>/secrets/` | opcional (ver secção 4) |
| `keys/PemKeys.java`, `keys/SigningKeyProvider.java` | `<base>/keys/` | sem dependências extra |
| `jwks/JwksController.java` | `<base>/jwks/` | caminho `/.well-known/jwks.json` |
| bloco em `config/SecurityConfig.java` (constante `JWKS_PATH` + regra `permitAll`) | `SecurityFilterChain` existente | ver secção 6, passo 4 |
| `keycloak/ClientAssertionFactory.java` | `<base>/keycloak/` | |
| `keycloak/KeycloakTokenClient.java`, `ServiceAccountTokens.java`, `TokenResponse.java`, `KeycloakAuthenticationException.java` | `<base>/keycloak/` | |
| método `withToken()` de `keycloak/KeycloakAdminClient.java` | onde estão as chamadas à Admin API | padrão a integrar (secção 7), não copiar a classe |
| `src/test/java/.../keys/PemKeysTest.java`, `keycloak/ClientAssertionFactoryTest.java`, `keycloak/KeycloakTokenClientTest.java`, `jwks/JwksControllerTest.java`, `TestFixtures.java`, `MutableClock.java` | `src/test/java/...` | ajustar package e caminhos |
| `src/test/resources/keys/*.pem`, `src/test/resources/secrets/*`, `application-test.yml` | `src/test/resources/` | chaves descartáveis só para testes; podes regenerá-las (secção 8) |

---

## 6. Passos de implementação, por ordem

1. **Dependências** (secção 3) e **configuração** (secção 4). Compila.
2. **Secrets e chave.** Copia `SecretProvider`, o provider escolhido, `PemKeys` e
   `SigningKeyProvider`. O `SigningKeyProvider` carrega a chave **uma vez no arranque**; se o secret
   faltar ou estiver mal formado, a aplicação deve falhar a arrancar (comportamento desejado).
   Verifica no log de arranque a linha `Chave de assinatura carregada: kid=..., RSA 3072 bits`.
3. **JWKS (tarefa 1).** Copia `JwksController`. O método devolve
   `new JWKSet(keys.publicKey()).toJSONObject()` com `Cache-Control: max-age=3600, public`.
   `publicKey()` é `rsaKey.toPublicJWK()`: garante que nunca sai a parte privada.
4. **Segurança do JWKS.** Na chain existente, antes das regras genéricas:
   ```java
   .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
   ```
   O Keycloak chama este endpoint sem credenciais. Se o serviço estiver atrás do Apigee, o proxy
   também tem de deixar passar este GET sem autenticação. Só este caminho fica público.
5. **Client assertion e token (tarefa 3).** Copia `ClientAssertionFactory`, `KeycloakTokenClient`,
   `ServiceAccountTokens`, `TokenResponse`, `KeycloakAuthenticationException`. O
   `KeycloakTokenClient` é um singleton com cache em memória; injeta-o onde as chamadas à Admin API
   são feitas.
6. **Integração com a Admin API** (secção 7).
7. **Testes** (secção 8) e **verificação** (secção 9).
8. **Entrega ao utilizador**: o URL final do JWKS (para a equipa de Keycloak configurar o cliente,
   tarefa 2) e a lista de variáveis de ambiente novas.

---

## 7. Integrar o token nas chamadas existentes à Admin API

Padrão de referência (`KeycloakAdminClient.withToken`): obter o token do `ServiceAccountTokens`,
enviá-lo como `Authorization: Bearer`, e num `401` descartar o token em cache e repetir **uma vez**.

```java
private <T> T withToken(Function<String, T> call) {
    try {
        return call.apply(tokens.accessToken());
    } catch (HttpClientErrorException.Unauthorized e) {   // 401
        tokens.invalidate();
        return call.apply(tokens.accessToken());
    }
}
```

Como aplicar conforme o cliente HTTP que o serviço já usa:

- **`RestClient` / `RestTemplate` / `WebClient`**: envolve cada chamada em `withToken(...)` e
  acrescenta `headers.setBearerAuth(token)`. Com `WebClient` a exceção é
  `WebClientResponseException.Unauthorized`.
- **`keycloak-admin-client`** (`org.keycloak:keycloak-admin-client`): não suporta `private_key_jwt`
  diretamente. Obtém o token com `KeycloakTokenClient` e passa-o a
  `KeycloakBuilder.builder().serverUrl(...).realm(...).authorization(accessToken).build()`
  (ver `BearerAuthFilter`, que acrescenta o prefixo `Bearer`). Como o token expira, constrói o
  objeto `Keycloak` por pedido ou renova-o quando `accessToken()` devolver um valor diferente.

Roles necessárias no token (dadas na tarefa 2 e incluídas via scope do cliente): `manage-users`
para alterar a pertença a grupos, `query-users`/`view-users` para pesquisar utilizadores e resolver
grupos. Se a Admin API responder **403** com o token válido, o problema é configuração do Keycloak
(roles em falta no token), não do sj-iam.

---

## 8. Testes a migrar

Copiar os testes marcados e `TestFixtures` / `MutableClock`. Os testes não precisam de Keycloak
nem de rede: `KeycloakTokenClientTest` e `KeycloakAdminClientTest` usam `MockRestServiceServer`.

Chaves de teste (descartáveis). Podes copiar as de `src/test/resources` ou gerar novas:

```bash
openssl genrsa -out src/test/resources/keys/test-key-pkcs1.pem 3072
openssl pkcs8 -topk8 -nocrypt -in src/test/resources/keys/test-key-pkcs1.pem -out src/test/resources/keys/test-key-pkcs8.pem
cp src/test/resources/keys/test-key-pkcs8.pem src/test/resources/secrets/iamsj_client_auth
printf 'iamsj-2026-10' > src/test/resources/secrets/iamsj_client_auth_kid
```

`JwksControllerTest` é um `@SpringBootTest` com `@ActiveProfiles("test")` e
`sjiam.secrets.provider=file` apontado para `src/test/resources/secrets`; adapta ao modo como o
serviço real configura os testes de contexto.

---

## 9. Verificação e critérios de aceitação

Com o serviço a correr (ambiente com acesso ao Keycloak):

```bash
# 1) JWKS: kty RSA, use sig, alg RS256, e AQAB, kid igual ao secret, sem campos d/p/q
curl -s https://<host>/.well-known/jwks.json | jq .
```

```bash
# 2) O Keycloak recusa uma assertion inválida (prova que exige assinatura)
curl -s -X POST https://<keycloak>/realms/CGA/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=iamsj \
  -d client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer \
  -d client_assertion=eyJhbGciOiJSUzI1NiJ9.e30.invalid
```

Checklist:

- [ ] `GET /.well-known/jwks.json` responde 200 sem autenticação, com uma chave `RSA/sig/RS256`,
      `kid` igual ao valor do secret `iamsj_client_auth_kid` e **sem** `d`, `p`, `q`, `dp`, `dq`, `qi`.
- [ ] O mesmo caminho responde 403/401 a outros métodos e os restantes caminhos mantêm a proteção
      que já tinham.
- [ ] A chave carrega no arranque a partir do Secret Manager (log `Chave de assinatura carregada`).
- [ ] A client assertion tem `alg=RS256`, `typ=JWT`, `kid` do JWKS, `iss=sub=iamsj`,
      `aud=<base-url>/realms/<realm>/protocol/openid-connect/token`, `jti` único, `exp - iat = 60s`.
- [ ] O pedido de token vai para `/protocol/openid-connect/token` com os quatro campos
      `grant_type`, `client_id`, `client_assertion_type`, `client_assertion`.
- [ ] Um fluxo real de associação a grupo gera **um** pedido de token e reutiliza-o nas chamadas
      seguintes durante ~4,5 minutos (log `Access token da service account 'iamsj' obtido` uma vez).
- [ ] Um 401 da Admin API provoca renovação do token e uma única repetição.
- [ ] Nenhum log contém a chave, a assertion ou o token.
- [ ] Os testes migrados passam.
- [ ] O utilizador recebeu o URL final do JWKS e a lista de variáveis de ambiente novas.

---

## 10. O que depende de outras equipas (não faças tu)

- **Keycloak (tarefa 2)**: cliente `iamsj` com Client authentication ON, Credentials = Signed Jwt
  (RS256), Keys = Use JWKS URL com o URL do passo 8 da secção 6, Service accounts ON, fluxos
  interativos OFF, roles `manage-users`/`query-users`/`view-users` do `realm-management` atribuídas
  à service account **e** incluídas no scope do cliente (o *Full scope allowed* está deprecated no
  Keycloak 26.8). O `README.md` deste projeto tem os passos de consola e o Terraform equivalente.
- **Apigee interno**: expor `GET /.well-known/jwks.json` do sj-iam sem autenticação, acessível
  pelo Keycloak.
- **GCP IAM**: `roles/secretmanager.secretAccessor` nos dois secrets para a service account do
  workload.

Enquanto a tarefa 2 não estiver feita, o sj-iam arranca e serve o JWKS, mas o pedido de token
falha com `invalid_client`. Isso é esperado.

---

## 11. Diagnóstico de erros do token endpoint

| Resposta do Keycloak | Causa provável | Correção |
|---|---|---|
| `invalid_request` / `HTTPS required` | realm com `sslRequired` e pedido em HTTP | usar HTTPS (produção) ou `sslRequired=none` (só dev) |
| `invalid_client` / `Can't identify client. Subject missing on JWT token` | assertion malformada ou sem `sub` | verificar `ClientAssertionFactory` |
| `invalid_client` / `Client authentication with signed JWT failed` + log do Keycloak "unable to load key with kid" | `kid` da assertion não existe no JWKS, ou o Keycloak não consegue chegar ao JWKS URL | kid igual nos dois lados; testar o JWKS URL **a partir** do Keycloak |
| `invalid_client` / `...Token audience doesn't match...` | `aud` diferente do URL que o Keycloak espera | `aud` = token endpoint tal como o Keycloak o publica em `/realms/<realm>` (`token-service` + `/token`); atenção a hostname/porta por trás de proxies |
| `invalid_client` / `Token reuse detected` | `jti` repetido | cada assertion tem `UUID.randomUUID()` |
| `invalid_client` / `Token is not active` ou `expired` | relógios desalinhados ou TTL muito curto | sincronizar NTP; `assertion-ttl` 60s é suficiente com relógios certos |
| Admin API **403** com token válido | roles não estão no token | tarefa 2: roles no scope do cliente (ver secção 10) |
| Admin API **401** | token expirado/revogado | `withToken` renova e repete uma vez; se persistir, ver `not-before` do realm |

---

## 12. O que NÃO migrar

`SjIamApplication`, o resto de `SecurityConfig`, `api/UserGroupController`,
`api/ApiExceptionHandler` (exceto o handler marcado, opcional), `keycloak/KeycloakAdminClient`
(exceto o padrão `withToken`), `KeycloakAdminClientTest`, `compose.yaml`, `keycloak/realm-cga.json`,
`scripts/`, `bruno/`, `secrets/`, `.claude/`.
