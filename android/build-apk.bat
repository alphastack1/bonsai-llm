@echo off
setlocal

echo ============================================
echo  Bonsai 27B - Android APK Build
echo ============================================
echo.

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ANDROID_HOME=C:\Users\Admin\AppData\Local\Android\Sdk"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"
set "LLAMA_DIR=app\src\main\cpp\llama.cpp"
set "LLAMA_COMMIT=62061f91088281e65071cc38c5f69ee95c39f14e"

echo [1/3] Checking Android toolchain...
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

echo [2/3] Preparing the tested PrismML llama.cpp runtime...
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

rem Version 2 deliberately ships no GGUF assets. The app downloads and verifies
rem the 4.43 GB model plus vision projector after installation.
if exist "app\src\main\assets\Bonsai-*.gguf" del /q "app\src\main\assets\Bonsai-*.gguf"

echo [3/3] Building signed arm64 release APK...
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
echo On first launch, open Models and download Bonsai 27B.
echo The resumable download requires about 4.95 GB free storage.
