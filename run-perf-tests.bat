@echo off
echo ============================================================
echo     TESTES DE DESEMPENHO - Sistema de Eventos
echo ============================================================
echo.

echo Compilando projeto...
javac -d target/classes -sourcepath src/main/java src/main/java/tests/PerformanceTests.java src/main/java/server/*.java src/main/java/client/*.java src/main/java/protocol/*.java src/main/java/model/*.java

if %ERRORLEVEL% NEQ 0 (
    echo Erro na compilacao!
    pause
    exit /b 1
)

echo Compilacao concluida
echo.

echo Executando testes de desempenho...
echo.

java -Xmx2G -cp target/classes tests.PerformanceTests > performance_results.txt

type performance_results.txt

echo.
echo ============================================================
echo Resultados salvos em: performance_results.txt
echo ============================================================
pause
