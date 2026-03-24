@echo off
REM Script para limpar tudo e recomecar do zero

echo Limpando projeto...
echo.

if exist data (
    rmdir /s /q data
    echo [OK] Pasta data removida
)

if exist target (
    rmdir /s /q target
    echo [OK] Pasta target removida
)

mkdir target\classes
mkdir data

echo.
echo [OK] Projeto limpo! Pastas recriadas.
echo.
echo Agora podes rodar:
echo   - run-server.bat (para iniciar o servidor)
echo   - run-client.bat (para iniciar o cliente)
echo.
pause
