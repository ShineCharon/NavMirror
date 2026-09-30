@echo off
setlocal EnableExtensions
chcp 65001 >nul
title Colin投屏 - 自用证书续期打包
set "ROOT=%~dp0"
set "CERTDIR=%TEMP%\NavMirror-certs"
set "WSL_CERT="
set "WSL_SCRIPT="
rem 域名优先读取环境变量，其次读取不入库的本地配置。
if not defined NAVMIRROR_DOMAIN if exist "%ROOT%navmirror.local.properties" (
  for /f "tokens=1,* delims==" %%a in ('findstr /b "domain=" "%ROOT%navmirror.local.properties"') do set "NAVMIRROR_DOMAIN=%%b"
)
if not defined NAVMIRROR_DOMAIN (
  echo 请先复制 navmirror.local.properties.example 为 navmirror.local.properties 并填写自己的域名。
  exit /b 1
)
if defined JAVA_HOME (
  if not exist "%JAVA_HOME%\bin\java.exe" (
    echo JAVA_HOME 无效，请配置 JDK 17。
    exit /b 1
  )
) else (
  where java >nul 2>nul
  if errorlevel 1 (
    echo 未找到 Java，请配置 JDK 17 的 JAVA_HOME 或 PATH。
    exit /b 1
  )
)

echo 此脚本生成含 TLS 私钥的自用 Debug APK，请勿公开分发。
echo [1/3] WSL 续期证书...
set "CERTFWD=%CERTDIR:\=/%"
set "SCRIPTFWD=%ROOT:\=/%renew_cert.sh"
for /f "delims=" %%p in ('wsl wslpath -a "%CERTFWD%"') do set "WSL_CERT=%%p"
for /f "delims=" %%p in ('wsl wslpath -a "%SCRIPTFWD%"') do set "WSL_SCRIPT=%%p"
if not defined WSL_CERT exit /b 1
if not defined WSL_SCRIPT exit /b 1
rem 直接传递参数，避免 bash -c 拼接含空格的 Windows 路径。
wsl --exec env "CERT_OUT=%WSL_CERT%" "NAVMIRROR_DOMAIN=%NAVMIRROR_DOMAIN%" bash --login "%WSL_SCRIPT%"
if errorlevel 1 (
  echo 续期失败，请按 docs\SETUP.md 检查首次签发和 WSL token 配置。
  exit /b 1
)

echo [2/3] 拷贝证书并构建自用 APK...
copy /y "%CERTDIR%\tesla_cert.pem" "%ROOT%app\src\main\assets\tesla_cert.pem" >nul
if errorlevel 1 exit /b 1
copy /y "%CERTDIR%\tesla_key.pem" "%ROOT%app\src\main\assets\tesla_key.pem" >nul
if errorlevel 1 exit /b 1
pushd "%ROOT%"
call gradlew.bat testDebugUnitTest assembleDebug --console=plain
if errorlevel 1 (
  popd
  exit /b 1
)
popd

echo [3/3] 装机并复制到桌面...
set "APK=%ROOT%app\build\outputs\apk\debug\app-debug.apk"
set "DEV="
set "DEVCNT=0"
where adb >nul 2>nul
if errorlevel 1 goto copy_apk
for /f "tokens=1,2" %%d in ('adb devices') do if "%%e"=="device" (
  if not defined DEV set "DEV=%%d"
  set /a DEVCNT+=1 >nul
)
if not defined DEV goto copy_apk
if %DEVCNT% GTR 1 (
  echo 检测到多台设备，跳过自动安装。请使用 adb -s 设备序列号 install -r 手动安装。
  goto copy_apk
)
adb -s "%DEV%" install -r "%APK%"
if errorlevel 1 exit /b 1
:copy_apk
copy /y "%APK%" "%USERPROFILE%\Desktop\NavMirror.apk" >nul
if errorlevel 1 exit /b 1
echo 完成：桌面 NavMirror.apk 仅供自用，勿上传 GitHub Releases。
pause
