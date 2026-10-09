#!/usr/bin/env bash
# APENAS TESTES LOCAIS
# Equivalente local da tarefa "Criação de chave RSA e carregamento no Google Cloud Secret".
#
# Em GCP, o Terraform cria:
#   tls_private_key { algorithm = "RSA", rsa_bits = 3072 }  -> secret iamsj_client_auth (PEM)
#   secret iamsj_client_auth_kid = "iamsj-YYYY-MM"
#
# Aqui gera-se o mesmo par de secrets como ficheiros em ./secrets, que é de onde o
# FileSecretProvider os lê quando sjiam.secrets.provider=file.
set -euo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)/secrets"
KEY_FILE="$DIR/iamsj_client_auth"
KID_FILE="$DIR/iamsj_client_auth_kid"

mkdir -p "$DIR"

if [[ -f "$KEY_FILE" && "${1:-}" != "--force" ]]; then
  echo "Secrets já existem em $DIR (usa --force para regenerar)."
  echo "kid atual: $(cat "$KID_FILE")"
  exit 0
fi

# LibreSSL escreve PKCS#1 (BEGIN RSA PRIVATE KEY), tal como o private_key_pem do Terraform.
# OpenSSL 3 escreve PKCS#8 (BEGIN PRIVATE KEY). O sj-iam aceita os dois formatos.
umask 077
openssl genrsa -out "$KEY_FILE" 3072 2>/dev/null
printf 'iamsj-%s' "$(date +%Y-%m)" > "$KID_FILE"

echo "Chave RSA 3072 escrita em $KEY_FILE"
echo "kid escrito em $KID_FILE: $(cat "$KID_FILE")"
