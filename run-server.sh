#!/bin/bash
# Script para rodar o servidor no Linux/Mac

echo "Compilando o projeto..."
javac -d target/classes -sourcepath src/main/java \
    src/main/java/server/Server.java \
    src/main/java/server/ServerState.java \
    src/main/java/server/ClientHandler.java \
    src/main/java/server/AuthManager.java \
    src/main/java/model/*.java \
    src/main/java/protocol/*.java

if [ $? -ne 0 ]; then
    echo "Erro na compilacao!"
    exit 1
fi

echo ""
echo "Iniciando servidor na porta 12345..."
echo ""
java -cp target/classes server.Server
