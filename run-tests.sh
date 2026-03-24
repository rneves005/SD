#!/bin/bash
set -e  # Para na primeira falha

echo "Compilando projeto..."
javac -d target/classes -sourcepath src/main/java \
  src/main/java/tests/Runner.java \
  src/main/java/server/*.java \
  src/main/java/client/*.java \
  src/main/java/protocol/*.java \
  src/main/java/model/*.java

echo ""
echo "Executando testes com otimizações JVM..."
echo ""

# Adicionar flags JVM para melhor performance:
java -cp target/classes \
  -XX:+TieredCompilation \
  -XX:TieredStopAtLevel=1 \
  -Xms512m -Xmx1024m \
  tests.Runner

echo ""
echo "Testes concluídos!"