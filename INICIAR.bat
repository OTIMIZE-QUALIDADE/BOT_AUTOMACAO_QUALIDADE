@echo off
chcp 65001 > nul
title Otimize Qualidade - SEI Test Automation
echo.
echo  ===============================================
echo   OTIMIZE QUALIDADE - Automacao de Testes SEI
echo  ===============================================
echo.

where python >nul 2>&1
if errorlevel 1 (
    echo [ERRO] Python nao encontrado. Instale Python 3.9+
    pause
    exit /b 1
)

echo [INFO] Verificando runtime Java...
if not exist "runtime\jdk\bin\java.exe" (
    echo [INFO] Baixando JDK 21 e Maven...
    python baixar_runtime.py
    if errorlevel 1 (
        echo [ERRO] Falha ao baixar runtime.
        pause
        exit /b 1
    )
)

echo [INFO] Iniciando servidor na porta 9871...
echo [INFO] Acesse: http://localhost:9871
echo.
start "" "http://localhost:9871"
python servidor.py
pause
