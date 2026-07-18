@echo off
setlocal EnableDelayedExpansion

echo ============================================
echo  Bonsai - Offline Android APK Build
echo ============================================
echo.

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ANDROID_HOME=C:\Users\Admin\AppData\Local\Android\Sdk"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"
set "LLAMA_DIR=app\src\main\cpp\llama.cpp"
set "LLAMA_COMMIT=62061f91088281e65071cc38c5f69ee95c39f14e"
set "BUNDLED_MODEL=app\src\main\assets\Bonsai-1.7B-Q1_0.gguf"
set "BUNDLED_PART=%BUNDLED_MODEL%.part"
set "BUNDLED_URL=https://huggingface.co/prism-ml/Bonsai-1.7B-gguf/resolve/main/Bonsai-1.7B-Q1_0.gguf?download=true"
set "BUNDLED_BYTES=248302272"
set "BUNDLED_SHA256=3d7c6c90dd98717a203adb22d5eacd2581850e40aa5327e144b97766cae5f7e3"

echo [1/4] Checking Android toolchain...
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: Android Studio JDK not found at %JAVA_HOME%
    exit /b 1
)
if not exist "%ANDROID_HOME%\platforms" (
    echo ERROR: Android SDK not found at %ANDROID_HOME%
    exit /b 1
)
set NDK_FOUND=0
for /d %%d in ("%ANDROID_HOME%\ndk\*") do set NDK_FOUND=1
if %NDK_FOUND%==0 (
    echo ERROR: Install the Android NDK from Android Studio's SDK Manager.
    exit /b 1
)
if not exist "C:\msys64\ucrt64\bin\g++.exe" (
    echo ERROR: MSYS2 UCRT64 g++ is required to generate the server's empty UI stub.
    echo Install MSYS2 and its UCRT64 GCC package, then retry.
    exit /b 1
)

echo [2/4] Preparing the tested PrismML llama.cpp runtime...
if not exist "%LLAMA_DIR%\CMakeLists.txt" (
    git clone --branch prism --depth 1 https://github.com/PrismML-Eng/llama.cpp.git "%LLAMA_DIR%"
    if errorlevel 1 exit /b 1
)
git -C "%LLAMA_DIR%" fetch --depth 1 origin %LLAMA_COMMIT%
if errorlevel 1 (
    echo ERROR: Could not fetch the pinned PrismML runtime.
    exit /b 1
)
git -C "%LLAMA_DIR%" checkout --detach %LLAMA_COMMIT%
if errorlevel 1 (
    echo ERROR: The runtime checkout has local changes. Preserve or remove them, then retry.
    exit /b 1
)

echo [3/4] Preparing the included Bonsai 1.7B offline model...
for %%F in ("app\src\main\assets\Bonsai-*.gguf") do (
    if exist "%%~fF" if /I not "%%~nxF"=="Bonsai-1.7B-Q1_0.gguf" del /q "%%~fF"
)
set "MODEL_OK=0"
if exist "%BUNDLED_MODEL%" (
    for %%F in ("%BUNDLED_MODEL%") do set "MODEL_BYTES=%%~zF"
    for /f %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 -LiteralPath '%BUNDLED_MODEL%').Hash.ToLowerInvariant()"') do set "MODEL_SHA256=%%H"
    if "!MODEL_BYTES!"=="%BUNDLED_BYTES%" if /I "!MODEL_SHA256!"=="%BUNDLED_SHA256%" set "MODEL_OK=1"
)
if "%MODEL_OK%"=="0" (
    if exist "%BUNDLED_MODEL%" del /q "%BUNDLED_MODEL%"
    curl.exe --fail --location --retry 5 --continue-at - --output "%BUNDLED_PART%" "%BUNDLED_URL%"
    if errorlevel 1 (
        echo ERROR: Could not download the included Bonsai 1.7B model.
        exit /b 1
    )
    for %%F in ("%BUNDLED_PART%") do set "MODEL_BYTES=%%~zF"
    for /f %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 -LiteralPath '%BUNDLED_PART%').Hash.ToLowerInvariant()"') do set "MODEL_SHA256=%%H"
    if not "!MODEL_BYTES!"=="%BUNDLED_BYTES%" (
        echo ERROR: Included model byte size does not match the official artifact.
        exit /b 1
    )
    if /I not "!MODEL_SHA256!"=="%BUNDLED_SHA256%" (
        echo ERROR: Included model checksum does not match the official artifact.
        exit /b 1
    )
    move /y "%BUNDLED_PART%" "%BUNDLED_MODEL%" >nul
)

echo [4/4] Building signed arm64 release APK...
call gradlew.bat --no-daemon assembleRelease
if errorlevel 1 (
    echo.
    echo BUILD FAILED. Review the Gradle/CMake error above.
    exit /b 1
)

echo.
echo ============================================
echo  BUILD SUCCESSFUL
echo ============================================
echo APK: app\build\outputs\apk\release\app-release.apk
echo Install: adb install -r app\build\outputs\apk\release\app-release.apk
echo.
echo Bonsai 1.7B is included for offline use. Larger models are optional downloads.
