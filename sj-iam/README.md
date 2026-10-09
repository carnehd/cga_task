# sj-iam

Microserviço Spring Boot (Java 21) que implementa as três tarefas do IAM SJ:

| Tarefa | O que faz | Onde está |
|---|---|---|
| 1. Endpoint well-known | `GET /.well-known/jwks.json` publica a chave pública RSA em JWK Set | `jwks/JwksController`, `keys/SigningKeyProvider` |
| 2. Cliente `iamsj` no Keycloak | Cliente confidencial autenticado por **Signed JWT**, chave lida do JWKS do sj-iam, service account com `manage-users`, `query-users`, `view-users` | `keycloak/realm-cga.json` (import local) e secção *Tarefa 2* abaixo |
| 3. Autenticação da service account | `client_credentials` + `private_key_jwt` (RFC 7523), token em cache, usado na Admin API | `keycloak/ClientAssertionFactory`, `keycloak/KeycloakTokenClient`, `keycloak/KeycloakAdminClient` |

A tarefa já feita (chave RSA 3072 + `iamsj_client_auth_kid` no Google Secret Manager) é
reproduzida localmente por `scripts/generate-local-secrets.sh`.

**Vais migrar isto para o sj-iam real com um assistente de código?** Dá-lhe o
[GUIA-MIGRACAO-LLM.md](GUIA-MIGRACAO-LLM.md): tem as perguntas a confirmar no repositório real, o
mapa ficheiro a ficheiro, os invariantes e a checklist de aceitação.

## Como funciona

```
                 (1) GET /.well-known/jwks.json  ──────────────┐
                                                               ▼
  sj-iam ──(3) POST /token  client_assertion (JWT RS256, kid)──▶ Keycloak (cliente iamsj, tarefa 2)
    │                                                           │ valida assinatura com a chave do JWKS
    │◀────────────────────── access_token ──────────────────────┘
    │
    └──(3) PUT /admin/realms/CGA/users/{id}/groups/{gid}  Authorization: Bearer <access_token>
```

A mesma chave RSA (secret `iamsj_client_auth`) serve os dois lados: a parte pública vai para
o JWKS, a privada assina a client assertion. O `kid` (secret `iamsj_client_auth_kid`,
formato `iamsj-YYYY-MM`) tem de ser o mesmo no JWKS e no cabeçalho da assertion.

## Pré-requisitos

- JDK 21 e Maven 3.9 (o `scripts/run-local.sh` aponta o `JAVA_HOME` para o JDK 21 do Homebrew)
- Docker Desktop (para o Keycloak local)
- `openssl` e `jq`

## Arrancar localmente

```bash
# 1) Secrets locais (equivalente ao Secret Manager): chave RSA 3072 + kid iamsj-YYYY-MM
./scripts/generate-local-secrets.sh

# 2) Keycloak local em http://localhost:8180 (admin/admin), com o realm CGA e o cliente iamsj importados
docker compose up -d
#    (a imagem 26.8 é grande; se o download demorar, usa uma tag já presente localmente:
#     KEYCLOAK_VERSION=26.0 docker compose up -d)

# 3) sj-iam em http://localhost:8081
./scripts/run-local.sh

# 4) Teste de ponta a ponta: JWKS -> token da service account -> alice entra no grupo sj-users
./scripts/e2e.sh
```

O Keycloak corre em Docker e o sj-iam no host, por isso o JWKS URL configurado no cliente é
`http://host.docker.internal:8081/.well-known/jwks.json`. Localmente, `host.docker.internal`
faz o papel do Apigee interno.

O realm local tem `sslRequired: none` porque tudo corre em HTTP; o Keycloak 26.8 recusa pedidos
de token em HTTP com o valor por omissão (`external`). No ambiente real o realm fica com TLS.

## Secrets: local vs Google Secret Manager

Não existe emulador oficial do Google Secret Manager. A solução é uma abstração com dois
providers, escolhidos por `sjiam.secrets.provider`:

| Provider | Classe | De onde lê |
|---|---|---|
| `file` (default) | `secrets/FileSecretProvider` | `./secrets/<nome>` (um ficheiro por secret) |
| `gcp` | `secrets/GoogleSecretManagerSecretProvider` | `projects/<project-id>/secrets/<nome>/versions/latest`, com Application Default Credentials |

O provider `file` é também o formato em que o GKE e o Cloud Run montam secrets do Secret
Manager como volumes, por isso pode ser usado em produção se preferirem montar os secrets em
vez de chamar a API. Para usar a API diretamente:

```bash
SPRING_PROFILES_ACTIVE=gcp GCP_PROJECT_ID=<projeto> KEYCLOAK_BASE_URL=https://<keycloak> mvn spring-boot:run
```

O resto da aplicação só conhece a interface `secrets/SecretProvider`.

### Formato do secret `iamsj_client_auth`

O Terraform `tls_private_key { algorithm = "RSA", rsa_bits = 3072 }` produz PEM. O loader
(`keys/PemKeys`) aceita:

- PKCS#1, `-----BEGIN RSA PRIVATE KEY-----` (o `private_key_pem` do Terraform; também o que o
  `openssl genrsa` do macOS escreve);
- PKCS#8, `-----BEGIN PRIVATE KEY-----` (o `private_key_pem_pkcs8` do Terraform; OpenSSL 3).

A chave pública é derivada da privada, por isso não é preciso guardar nem a chave pública nem
um certificado. Se o secret real estiver noutro formato (por exemplo um JKS em base64, como nos
exemplos das tarefas), só é preciso trocar o parser em `SigningKeyProvider`.

## Configuração

| Propriedade | Default | Descrição |
|---|---|---|
| `sjiam.secrets.provider` | `file` | `file` ou `gcp` |
| `sjiam.secrets.key-secret-name` | `iamsj_client_auth` | secret com a chave privada PEM |
| `sjiam.secrets.kid-secret-name` | `iamsj_client_auth_kid` | secret com o kid |
| `sjiam.secrets.file.directory` | `./secrets` | diretório dos secrets (provider `file`) |
| `sjiam.secrets.gcp.project-id` | `$GCP_PROJECT_ID` | projeto GCP (provider `gcp`) |
| `sjiam.keycloak.base-url` | `http://localhost:8180` | URL base do Keycloak |
| `sjiam.keycloak.realm` | `CGA` | realm |
| `sjiam.keycloak.client-id` | `iamsj` | client id da service account |
| `sjiam.keycloak.assertion-ttl` | `60s` | validade da client assertion |
| `sjiam.keycloak.token-refresh-skew` | `30s` | antecedência da renovação do access token |

## Tarefa 2 no Keycloak real

O ficheiro `keycloak/realm-cga.json` é a versão importável da configuração pedida. Na consola de
administração do Keycloak corresponde a:

- **Clients > Create client**: Client ID `iamsj`, Client authentication **ON**, Authorization
  **OFF**, Standard flow **OFF**, Direct access grants **OFF**, Service accounts roles **ON**,
  OAuth 2.0 Device Authorization Grant **ON** (pedido na tarefa; não é necessário para serviço a
  serviço, convém confirmar).
- **Credentials**: Client Authenticator **Signed Jwt**, Signature algorithm **RS256**.
- **Keys**: Use JWKS URL **ON**, JWKS URL = URL do `/.well-known/jwks.json` do sj-iam exposto
  no Apigee interno.
- **Service account roles > Assign role > Filter by clients**: `realm-management` →
  `manage-users`, `query-users`, `view-users`.
- **Client scopes > iamsj-dedicated > Scope**: Full scope allowed **OFF** e as mesmas três roles do
  `realm-management` atribuídas ao scope do cliente. A Admin API autoriza pelas roles presentes no
  access token, e o Keycloak 26.8 marca o *Full scope allowed* como deprecated.

Com o provider Terraform do Keycloak, o equivalente é:

```hcl
resource "keycloak_openid_client" "iamsj" {
  realm_id                     = keycloak_realm.cga.id
  client_id                    = "iamsj"
  access_type                  = "CONFIDENTIAL"
  client_authenticator_type    = "client-jwt"
  service_accounts_enabled     = true
  standard_flow_enabled        = false
  direct_access_grants_enabled = false
  full_scope_allowed           = false
  oauth2_device_authorization_grant_enabled = true
  extra_config = {
    "use.jwks.url"                    = "true"
    "jwks.url"                        = "https://<apigee-interno>/sj-iam/.well-known/jwks.json"
    "token.endpoint.auth.signing.alg" = "RS256"
  }
}

data "keycloak_openid_client" "realm_management" {
  realm_id  = keycloak_realm.cga.id
  client_id = "realm-management"
}

# Roles que a service account tem
resource "keycloak_openid_client_service_account_role" "iamsj" {
  for_each                = toset(["manage-users", "query-users", "view-users"])
  realm_id                = keycloak_realm.cga.id
  service_account_user_id = keycloak_openid_client.iamsj.service_account_user_id
  client_id               = data.keycloak_openid_client.realm_management.id
  role                    = each.value
}

# Roles que entram no access token (scope do cliente, em vez de Full scope allowed)
data "keycloak_role" "realm_management" {
  for_each  = toset(["manage-users", "query-users", "view-users"])
  realm_id  = keycloak_realm.cga.id
  client_id = data.keycloak_openid_client.realm_management.id
  name      = each.value
}

resource "keycloak_generic_role_mapper" "iamsj_scope" {
  for_each  = data.keycloak_role.realm_management
  realm_id  = keycloak_realm.cga.id
  client_id = keycloak_openid_client.iamsj.id
  role_id   = each.value.id
}
```

## Diferenças face aos exemplos das tarefas

- **Chave em PEM, não em JKS.** Os exemplos carregam um JKS com alias e password; a tarefa já
  feita gera PEM com Terraform. O `PemKeys` lê PEM e deriva a chave pública.
- **`kid` vem do secret.** O exemplo da tarefa 1 tem o kid fixo e o da tarefa 3 usa o número
  de série do certificado. Aqui o kid é sempre `iamsj_client_auth_kid`, nos dois sítios.
- **`aud` e destino do POST.** O exemplo da tarefa 3 faz o POST ao URL do realm. O pedido vai
  ao token endpoint (`/protocol/openid-connect/token`) e a `aud` é esse mesmo URL, como pede a
  RFC 7523 e como o Keycloak recomenda nas versões recentes.
- **Uma só chave carregada.** O exemplo da tarefa 3 voltava a abrir o keystore; aqui o
  `SigningKeyProvider` é o único ponto de carregamento e é partilhado pelas tarefas 1 e 3.
- **Cache do token.** O `KeycloakTokenClient` guarda o access token e renova-o 30 s antes de
  expirar; a Admin API renova-o também se receber 401.

## Coleção Bruno (pedidos individuais)

Em `bruno/` há uma coleção [Bruno](https://www.usebruno.com) com o ambiente `local`:

| Pasta | O que testa |
|---|---|
| `01-sj-iam` | health, JWKS (tarefa 1), associar utilizador a grupo e listar grupos (tarefa 3), 404 e 403 |
| `02-keycloak-service-account` | o que o sj-iam faz por dentro: gera e assina a client assertion, pede o token, usa-o na Admin API; assertion inválida; Admin API sem token |
| `03-keycloak-admin` | verificação da tarefa 2 com o admin local: cliente `iamsj`, scope mappings, roles da service account |

Na app: *Open Collection* → pasta `bruno`, escolher o ambiente **local**. O pedido
*Token com client assertion* assina o JWT num pre-request script com a chave de `../secrets`,
por isso a coleção tem de correr em **Developer Mode** (Collection settings → Script).

Na linha de comandos, com tudo a correr:

```bash
cd bruno && npx @usebruno/cli run --env local -r --sandbox developer
```

## Testes

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn test
```

- `PemKeysTest`: PKCS#1 e PKCS#8 dão a mesma chave; a pública derivada verifica assinaturas.
- `JwksControllerTest`: endpoint público, campos `kty/kid/use/alg/n/e`, nunca `d/p/q/dp/dq/qi`.
- `ClientAssertionFactoryTest`: cabeçalho RS256 + kid do JWKS, claims RFC 7523, jti único.
- `KeycloakTokenClientTest`: pedido `client_credentials` com assertion verificável, cache,
  renovação antecipada, erros do Keycloak com a descrição.
- `KeycloakAdminClientTest`: Bearer token na Admin API, renovação após 401, 404 para
  utilizador inexistente.

As chaves em `src/test/resources` são descartáveis e servem só para os testes.

## O que migrar para o sj-iam real

Os ficheiros estão marcados no código: `//USAR CODIGO` é código final a migrar, `// APENAS TESTES
LOCAIS` é andaime deste projeto. Nos ficheiros mistos a marca está ao nível do bloco.

```bash
grep -rn "USAR CODIGO" src pom.xml
```

| Migrar (USAR CODIGO) | Papel |
|---|---|
| `config/SjIamProperties`, `config/AppConfig` | configuração `sjiam.*` e bean `Clock` |
| `secrets/SecretProvider`, `SecretNotFoundException`, `GoogleSecretManagerSecretProvider` | fonte de secrets; `FileSecretProvider` só se os secrets forem montados como ficheiros |
| `keys/PemKeys`, `keys/SigningKeyProvider` | chave RSA e kid a partir dos secrets |
| `jwks/JwksController` + regra `permitAll` e constante `JWKS_PATH` do `SecurityConfig` | tarefa 1 |
| `keycloak/ClientAssertionFactory`, `KeycloakTokenClient`, `ServiceAccountTokens`, `TokenResponse`, `KeycloakAuthenticationException` | tarefa 3 |
| padrão `withToken()` do `KeycloakAdminClient` | Bearer + renovação após 401 nas chamadas já existentes à Admin API |
| testes `PemKeysTest`, `ClientAssertionFactoryTest`, `KeycloakTokenClientTest`, `JwksControllerTest`, `TestFixtures`, `MutableClock` e os recursos em `src/test/resources` | testes das classes migradas |
| bloco `sjiam:` do `application.yml`, `application-gcp.yml`, dependências marcadas no `pom.xml` | configuração e dependências |

| Só local (APENAS TESTES LOCAIS) | Porquê |
|---|---|
| `SjIamApplication`, resto do `SecurityConfig` | o sj-iam real já tem arranque e segurança próprios |
| `api/UserGroupController`, `api/ApiExceptionHandler`, `keycloak/KeycloakAdminClient`, `KeycloakAdminClientTest` | demonstração da Admin API; o sj-iam real já associa utilizadores a grupos |
| `compose.yaml`, `keycloak/realm-cga.json`, `scripts/`, `bruno/`, `secrets/` | Keycloak local, secrets locais e ferramentas de teste |

## Antes de ir para o ambiente real

- Proteger `/api/**` (neste scaffold está aberto; ver `config/SecurityConfig`). O JWKS
  mantém-se público.
- Confirmar o formato do secret `iamsj_client_auth` no Secret Manager (PEM PKCS#1 ou PKCS#8).
- Expor `/.well-known/jwks.json` no Apigee interno e colocar esse URL no cliente Keycloak.
- Ponderar rotação: publicar a chave antiga e a nova no JWKS durante a transição (hoje o
  JWKS tem uma só chave).
- Relógios sincronizados entre sj-iam e Keycloak (a assertion vale 60 s).
- Validado localmente com Keycloak 26.8 (e 26.0). Em 26.8 o realm exige HTTPS por omissão e o
  *Full scope allowed* está deprecated; o `realm-cga.json` já reflete as duas coisas.
