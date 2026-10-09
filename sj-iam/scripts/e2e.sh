#!/usr/bin/env bash
# APENAS TESTES LOCAIS
# Teste de ponta a ponta local das tarefas 1, 2 e 3:
#   1. o sj-iam publica o JWKS;
#   2. o Keycloak (cliente iamsj) valida a client assertion com a chave do JWKS;
#   3. o sj-iam usa o access token da service account na Admin API para associar
#      a utilizadora alice ao grupo sj-users.
#
# Pré-requisitos: docker compose up -d  e  o sj-iam a correr na porta 8081.
set -euo pipefail

APP="${APP_URL:-http://localhost:8081}"
KC="${KEYCLOAK_URL:-http://localhost:8180}"
USER_NAME="${1:-alice}"
GROUP_NAME="${2:-sj-users}"

echo "== 0) Keycloak e sj-iam acessíveis?"
curl -fsS -o /dev/null "$KC/realms/CGA" && echo "Keycloak OK ($KC/realms/CGA)"
curl -fsS -o /dev/null "$APP/actuator/health" && echo "sj-iam OK ($APP/actuator/health)"

echo
echo "== 1) JWKS publicado pelo sj-iam (tarefa 1)"
curl -fsS "$APP/.well-known/jwks.json" | jq .

echo
echo "== 2) + 3) Associar '$USER_NAME' ao grupo '$GROUP_NAME' (tarefas 2 e 3)"
curl -fsS -X PUT -o /dev/null -w "HTTP %{http_code}\n" "$APP/api/users/$USER_NAME/groups/$GROUP_NAME"

echo
echo "== Grupos de '$USER_NAME' segundo o Keycloak"
curl -fsS "$APP/api/users/$USER_NAME/groups" | jq .
