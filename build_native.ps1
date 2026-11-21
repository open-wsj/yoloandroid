# 重新编译 arm64-v8a 原生推理库（CPU 版）
# 用法：在项目根目录执行  powershell -ExecutionPolicy Bypass -File .\build_native.ps1
# 说明：中文路径下 CMake 3.22 会崩溃，因此直接用 NDK clang 编译出预编译 .so，
#      Gradle 只负责把 app/src/main/jniLibs 打入 APK。
#      ncnn 使用 CPU-only 包（不含 Vulkan/glslang），无需 glslang 系列静态库。

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = "C:\Users\wsj\AppData\Local\Android\Sdk" }
$ndk = Join-Path $sdk "ndk\28.2.13676358"
if (-not (Test-Path $ndk)) { throw "NDK 未找到：$ndk" }

$cxx = Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android24-clang++.cmd"
$strip = Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"
$ncnnDir = Join-Path $root "third_party\ncnn-20250503-android\arm64-v8a"
$cppDir = Join-Path $root "app\src\main\cpp"
$outDir = Join-Path $root "app\src\main\jniLibs\arm64-v8a"
New-Item -ItemType Directory -Force $outDir | Out-Null
$out = Join-Path $outDir "libyolo11ncnn.so"

& $cxx -shared -fPIC -O2 -std=c++17 `
    -I (Join-Path $ncnnDir "include\ncnn") `
    -o $out `
    (Join-Path $cppDir "yolo.cpp") `
    (Join-Path $cppDir "yolo_cls.cpp") `
    (Join-Path $cppDir "yolo11ncnn_jni.cpp") `
    (Join-Path $cppDir "simpleomp.cpp") `
    (Join-Path $ncnnDir "lib\libncnn.a") `
    -landroid -llog -ldl `
    -static-libstdc++
if ($LASTEXITCODE -ne 0) { throw "编译失败，退出码 $LASTEXITCODE" }

& $strip --strip-unneeded $out
Write-Host "已生成: $out"
