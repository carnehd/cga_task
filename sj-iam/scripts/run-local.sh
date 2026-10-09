#!/usr/bin/env bash
# APENAS TESTES LOCAIS
# Arranca o sj-iam localmente (porta 8081) com o JDK 21.
# Lê os secrets de ./secrets (gera-os primeiro com scripts/generate-local-secrets.sh).
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  for candidate in /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home "$HOME/.sdkman/candidates/java/21"*; do
    if [[ -x "$candidate/bin/java" ]]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
echo "JAVA_HOME=${JAVA_HOME:-<não definido>}"

[[ -f secrets/iamsj_client_auth ]] || ./scripts/generate-local-secrets.sh

exec mvn -q spring-boot:run "$@"
