@echo off
setlocal

REM Usage: scripts\generate-certificates.bat [server-dns-name] [server-ip]
set "SERVER_NAME=%~1"
if "%SERVER_NAME%"=="" set "SERVER_NAME=localhost"
set "SERVER_IP=%~2"
if "%SERVER_IP%"=="" set "SERVER_IP=127.0.0.1"
set "STORE_PASSWORD=%SECURECHAT_STORE_PASSWORD%"
if "%STORE_PASSWORD%"=="" set "STORE_PASSWORD=changeit"
set "CERT_DIR=config\certs"
set "KEYSTORE=%CERT_DIR%\server-keystore.p12"
set "TRUSTSTORE=%CERT_DIR%\client-truststore.p12"
set "CERTIFICATE=%CERT_DIR%\server-certificate.pem"

if not exist "%CERT_DIR%" mkdir "%CERT_DIR%"
if exist "%KEYSTORE%" goto already_exists
if exist "%TRUSTSTORE%" goto already_exists
if exist "%CERTIFICATE%" goto already_exists

keytool -genkeypair -alias securechat-server -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 3650 -dname "CN=%SERVER_NAME%, OU=SecureChat, O=University Project, C=LK" -ext "SAN=dns:%SERVER_NAME%,dns:localhost,ip:%SERVER_IP%,ip:127.0.0.1" -ext "EKU=serverAuth" -storetype PKCS12 -keystore "%KEYSTORE%" -storepass "%STORE_PASSWORD%" -keypass "%STORE_PASSWORD%" -noprompt
if errorlevel 1 exit /b 1

keytool -exportcert -alias securechat-server -keystore "%KEYSTORE%" -storepass "%STORE_PASSWORD%" -rfc -file "%CERTIFICATE%"
if errorlevel 1 exit /b 1

keytool -importcert -alias securechat-server -file "%CERTIFICATE%" -keystore "%TRUSTSTORE%" -storetype PKCS12 -storepass "%STORE_PASSWORD%" -noprompt
if errorlevel 1 exit /b 1

echo Created %KEYSTORE%
echo Created %TRUSTSTORE%
echo Certificate names: %SERVER_NAME% and %SERVER_IP%
exit /b 0

:already_exists
echo Certificate files already exist in %CERT_DIR%.
echo Move or delete those three generated files before creating a new identity.
exit /b 1
