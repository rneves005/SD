@echo off
REM Script para rodar testes automatizados

echo Compilando projeto...
javac -d target/classes -sourcepath src/main/java src/main/java/tests/Runner.java src/main/java/server/*.java src/main/java/client/*.java src/main/java/protocol/*.java src/main/java/model/*.java

if %errorlevel% neq 0 (
    echo Erro na compilacao!
    pause
    exit /b 1
)

echo.
echo Executando testes...
echo.
java -cp target/classes tests.Runner

pause
