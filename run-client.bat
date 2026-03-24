@echo off
REM Script para rodar o cliente no Windows

echo Compilando o projeto...
javac -d target/classes -sourcepath src/main/java src/main/java/client/*.java src/main/java/protocol/*.java src/main/java/model/*.java

if %errorlevel% neq 0 (
    echo Erro na compilacao!
    pause
    exit /b 1
)

echo.
echo Iniciando cliente (conectando a localhost:12345)...
echo.
java -cp target/classes client.ClientUI

pause
