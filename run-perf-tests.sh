#!/bin/bash
# Script para executar testes de desempenho

echo "════════════════════════════════════════════════════════"
echo "    TESTES DE DESEMPENHO - Sistema de Eventos"
echo "════════════════════════════════════════════════════════"
echo ""

# Compilar projeto
echo "→ Compilando projeto..."
javac -d target/classes -sourcepath src/main/java \
    src/main/java/tests/PerformanceTests.java \
    src/main/java/server/*.java \
    src/main/java/client/*.java \
    src/main/java/protocol/*.java \
    src/main/java/model/*.java

if [ $? -ne 0 ]; then
    echo "❌ Erro na compilação!"
    exit 1
fi

echo "✅ Compilação concluída"
echo ""

# Executar testes com mais memória para evitar OutOfMemory em testes de stress
echo "→ Executando testes de desempenho..."
echo ""

java -Xmx2G -cp target/classes tests.PerformanceTests | tee performance_results.txt

echo ""
echo "════════════════════════════════════════════════════════"
echo "Resultados salvos em: performance_results.txt"
echo "════════════════════════════════════════════════════════"
