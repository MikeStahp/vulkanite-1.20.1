@echo off
setlocal enabledelayedexpansion

:: =====================================================================
:: CONFIGURACIÓN DE RUTAS (Edita esto si tus carpetas están en otro lugar)
:: =====================================================================
set SOURCE_FILE="C:\Users\PCGAMER\Documents\GitHub\vulkanite-1.20.1\dlss_bridge\dlss_wrapper.cpp"
set OUTPUT_DIR=..\run
set OUTPUT_NAME=vulkanite_dlss_bridge.dll

:: Rutas al SDK de Vulkan (VULKAN_SDK se define automáticamente al instalarlo)
set VULKAN_INCLUDE=%VULKAN_SDK%\Include
set VULKAN_LIB=%VULKAN_SDK%\Lib

:: Ruta al SDK de DLSS (Ajusta esto según dónde hayas extraído el SDK de NVIDIA)
set DLSS_SDK_DIR=.\dlss_sdk
set DLSS_INCLUDE="C:\Users\PCGAMER\Documents\GitHub\vulkanite-1.20.1\dlss_bridge\Include"
set DLSS_LIB="C:\Users\PCGAMER\Documents\GitHub\vulkanite-1.20.1\dlss_bridge\Lib\x64"

:: Ruta al bin de Java para que JNA lo encuentre si no lo hace en el working dir
set JAVA_BIN_DIR="C:\Program Files\Java\jdk-21\bin"
:: =====================================================================

echo [1/4] Buscando el compilador de Visual Studio (MSVC)...
:: Utiliza vswhere para localizar de forma segura el compilador de VS
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"

if not exist "%VSWHERE%" (
    echo [ERROR] No se encontro vswhere.exe.
    echo Asegurate de tener Visual Studio instalado con la carga de trabajo "Desarrollo para el escritorio con C++".
    exit /b 1
)

:: Buscar la ruta de instalación de VS que tenga las herramientas de C++ x64
for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do (
    set VS_INSTALL_DIR=%%i
)

if "%VS_INSTALL_DIR%"=="" (
    echo [ERROR] No se pudo encontrar una instalacion valida de Visual Studio con soporte C++.
    exit /b 1
)

set "VCVARS=%VS_INSTALL_DIR%\VC\Auxiliary\Build\vcvars64.bat"
if not exist "%VCVARS%" (
    echo [ERROR] No se encontro vcvars64.bat en: %VCVARS%
    exit /b 1
)

echo [2/4] Inicializando entorno de compilacion (64-bits para Minecraft)...
call "%VCVARS%" >nul

:: Crear la carpeta run si no existe
if not exist "%OUTPUT_DIR%" (
    mkdir "%OUTPUT_DIR%"
)

echo [3/4] Compilando %SOURCE_FILE%...
if "%VULKAN_SDK%"=="" (
    echo [ADVERTENCIA] La variable de entorno VULKAN_SDK no esta definida.
    echo Asegurate de tener el Vulkan SDK instalado.
)

:: Ejecutar compilador cl.exe
:: /LD = Crear DLL | /O2 = Optimizar | /MT = Usar MSVCRT estatico (Match nvsdk_ngx_s.lib) | /EHsc = Manejo de excepciones
:: /std:c++17 = Usar estandar C++17 para std::string_view, std::optional, etc.
:: Linkeamos con nvsdk_ngx_s.lib (static) que fue compilado con /MT
cl.exe /nologo /O2 /LD /EHsc /MT /std:c++17 /DNV_WINDOWS ^
    /I"%VULKAN_INCLUDE%" ^
    /I"%DLSS_INCLUDE%" ^
    "%SOURCE_FILE%" ^
    /link /OUT:"%OUTPUT_DIR%\%OUTPUT_NAME%" ^
    /LIBPATH:"%VULKAN_LIB%" ^
    /LIBPATH:"%DLSS_LIB%" ^
    vulkan-1.lib nvsdk_ngx_s.lib advapi32.lib user32.lib

:: Comprobar si falló la compilación
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] La compilacion fallo. Revisa los errores arriba.
    exit /b 1
)

echo [4/4] Limpiando archivos intermedios...
del *.obj *.exp *.lib 2>nul
:: Si el linker genera los archivos .lib y .exp en la carpeta run, limpiarlos ahi tambien:
del "%OUTPUT_DIR%\*.exp" "%OUTPUT_DIR%\*.lib" 2>nul

echo [5/5] Copiando a la carpeta de Java...
copy /Y "%OUTPUT_DIR%\%OUTPUT_NAME%" %JAVA_BIN_DIR% >nul
if %ERRORLEVEL% NEQ 0 (
    echo [ADVERTENCIA] No se pudo copiar a la carpeta de Java. Revisa los permisos de administrador.
) else (
    echo [OK] DLL copiada a %JAVA_BIN_DIR%
)

echo.
echo =======================================================
echo [EXITO] DLL compilada correctamente en: 
echo %CD%\%OUTPUT_DIR%\%OUTPUT_NAME%
echo =======================================================
