#!/bin/bash
# Script para rodar o cliente no Linux/Mac

echo "Compilando o projeto..."
javac -d target/classes -sourcepath src/main/java \
    src/main/java/client/*.java \
    src/main/java/protocol/*.java \
    src/main/java/model/*.java

if [ $? -ne 0 ]; then
    echo "Erro na compilacao!"
    exit 1
fi

echo ""
echo "Iniciando cliente (conectando a localhost:12345)..."
echo ""
java -cp target/classes client.ClientUI
