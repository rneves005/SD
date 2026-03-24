#!/bin/bash
# Script para limpar tudo e recomecar do zero

echo "Limpando projeto..."
echo ""

rm -rf data target
echo "[OK] Pastas data e target removidas"

mkdir -p target/classes data
echo "[OK] Pastas recriadas"

echo ""
echo "[OK] Projeto limpo!"
echo ""
echo "Agora podes rodar:"
echo "  - ./run-server.sh (para iniciar o servidor)"
echo "  - ./run-client.sh (para iniciar o cliente)"
echo ""
