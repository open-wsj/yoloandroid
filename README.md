# YOLO11 Ncnn Android

基于 [ncnn](https://github.com/Tencent/ncnn) 的 Android 端 YOLO11 实时目标检测 / 图像分类应用，纯 CPU 推理（arm64-v8a），支持「检测」与「分类」两种工作模式。

- 仓库地址：https://gitee.com/open-zynq/yoloandroid
- 当前版本：**v1.0.0**（versionCode 2）

## 功能特性

| 模式 | 功能 |
| --- | --- |
| 检测 | 摄像头实时检测（YOLO11m，80 类 COCO） |
| 检测 | 相册导入视频，逐帧检测并叠加检测框，支持暂停/继续/停止 |
| 分类 | 相册导入图片分类（YOLO11m-cls，ImageNet 1000 类，Top-5） |
| 分类 | 摄像头拍照后即时分类 |
| 设置 | 检测/分类模式互斥切换 |
| 设置 | 自动枚举设备全部逻辑摄像头（前置/后置等），切换后自动重连并刷新预览 |

## 环境依赖

### 推荐开发环境（已验证）

| 组件 | 版本 |
| --- | --- |
| 操作系统 | Windows 10/11 x64 |
| Android Studio | 2026.x（自带 JBR 25） |
| Android Gradle Plugin | 9.3.3 |
| Gradle | 9.5.0（使用仓库自带 `gradlew`） |
| JDK | 21+（推荐直接使用 Android Studio 自带 JBR 25） |
| Android SDK | compileSdk / targetSdk 36，Build-Tools 36.0.0 |
| Android NDK | 28.2.13676358 |
| ncnn | **20250503-android（CPU-only 包）** |
| Python（仅换模型时需要） | 3.11（uv 管理） |
| ultralytics / pnnx | 8.4.x / 最新（pip 安装） |

### 运行设备要求

- Android 7.0（API 24）及以上
- **arm64-v8a** 架构（当前只打包 64 位，32 位设备无法安装）
- 具备摄像头（后置或前置均可）

### 关键说明

- **不需要 CMake**：工程不使用 `externalNativeBuild`，原生 `.so` 由 `build_native.ps1` 直接调用 NDK clang 编译后放入 `app/src/main/jniLibs/arm64-v8a/`。
- **不需要 OpenCV**：预处理（letterbox、中心裁剪、RGBA→RGB）全部用 ncnn 自带接口完成。
- 模型文件已内置在 `app/src/main/assets/`，克隆后无需下载即可编译。

## 目录结构

```
android_ncnn/
├── app/
│   ├── build.gradle.kts              # 应用配置、签名配置、版本号
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/                   # 模型与标签（随 APK 打包）
│       │   ├── yolo11m.ncnn.{param,bin}       # 检测模型（YOLO11m，80 类）
│       │   ├── yolo11m-cls.ncnn.{param,bin}   # 分类模型（YOLO11m-cls，1000 类）
│       │   ├── labels.txt            # COCO 80 类标签
│       │   └── imagenet_labels.txt   # ImageNet 1000 类标签
│       ├── cpp/
│       │   ├── yolo.h / yolo.cpp     # 检测：letterbox、解码、NMS
│       │   ├── yolo_cls.h / .cpp     # 分类：中心裁剪、Softmax Top-K
│       │   ├── yolo11ncnn_jni.cpp    # JNI 入口（检测 + 分类）
│       │   └── simpleomp.cpp         # 轻量 OpenMP 运行时（替代 libomp）
│       ├── java/com/example/yolo11ncnn/
│       │   ├── MainActivity.java     # 主界面：双模式、实时/视频检测、拍照/相册分类
│       │   ├── SettingsActivity.java # 设置页：模式选择、摄像头枚举
│       │   ├── Yolo11Detector.java   # 检测 JNI 封装
│       │   ├── Classifier.java       # 分类 JNI 封装
│       │   ├── OverlayView.java      # 检测框与标签绘制（含避让逻辑）
│       │   └── AppPrefs.java         # 模式/摄像头持久化
│       └── res/                      # 布局、字符串、自适应图标
├── third_party/ncnn-20250503-android/arm64-v8a/  # ncnn 静态库与头文件
├── gradle/                           # Gradle Wrapper
├── build_native.ps1                  # 编译 libyolo11ncnn.so
├── build.gradle.kts / settings.gradle.kts
├── gradle.properties                 # JVM 参数、JDK 路径、中文路径放行
├── .gitignore
├── CHANGELOG.md
└── README.md
```

## 分步复现指南

### 1. 克隆仓库

```powershell
git clone https://gitee.com/open-zynq/yoloandroid.git
cd yoloandroid
```

### 2. 配置本地 SDK 路径

在项目根目录创建 `local.properties`（已被 `.gitignore` 忽略）：

```properties
sdk.dir=C\:\\Users\\<你的用户名>\\AppData\\Local\\Android\\Sdk
```

同时检查 `gradle.properties` 中的 JDK 路径，指向本机 Android Studio 自带的 JBR：

```properties
org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr
```

> 如果本机路径不同，请改成自己的路径；也可以删除该行，改用系统 `JAVA_HOME`。

### 3. 安装 NDK

在 Android Studio → SDK Manager → SDK Tools 中勾选 **NDK (Side by side)**，安装 **28.2.13676358**。
若安装了其他版本，请同步修改 `build_native.ps1` 中的 `ndk` 变量与 `app/build.gradle.kts` 的 `ndkVersion`。

### 4. 编译原生推理库

```powershell
powershell -ExecutionPolicy Bypass -File .\build_native.ps1
```

成功后会生成 `app/src/main/jniLibs/arm64-v8a/libyolo11ncnn.so`（约 4.1MB）。
脚本依次编译 `yolo.cpp`、`yolo_cls.cpp`、`yolo11ncnn_jni.cpp`、`simpleomp.cpp`，并静态链接 `libncnn.a`。

### 5. 构建 APK

调试版：

```powershell
.\gradlew.bat assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

正式版（需先配置签名，见下一节）：

```powershell
.\gradlew.bat assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

### 6. 安装到手机

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.yolo11ncnn/.MainActivity
```

首次启动会申请相机权限（分类模式的相册/视频选择使用系统 Photo Picker，不需要存储权限）。

### 7. 首次使用

1. 点击右上角「设置」，选择「检测」或「分类」；
2. 在「采集摄像头」下拉中选择前置/后置（默认后置），点「完成」返回；
3. 检测模式：实时画面自动出框，底部「导入视频」可逐帧检测；
4. 分类模式：底部「相册」选择图片或「拍照」后自动显示 Top-5 结果。

## 模型导出与替换

模型文件已随仓库提供，仅在需要更换模型（如 yolo11n / 自定义训练模型）时执行以下步骤。

### 1. 创建 Python 环境

```powershell
uv venv .venv-model --python 3.11
$env:UV_DEFAULT_INDEX="https://pypi.tuna.tsinghua.edu.cn/simple"
uv pip install --python .venv-model\Scripts\python.exe ultralytics pnnx
```

### 2. 导出检测模型（640 输入）

```powershell
.venv-model\Scripts\python.exe -c "from ultralytics import YOLO; YOLO('yolo11m.pt').export(format='ncnn', imgsz=640)"
```

产物在 `yolo11m_ncnn_model/model.ncnn.param` 与 `model.ncnn.bin`。

### 3. 导出分类模型（224 输入，附标签）

```powershell
.venv-model\Scripts\python.exe -c "from ultralytics import YOLO; m=YOLO('yolo11m-cls.pt'); open('imagenet_labels.txt','w',encoding='utf-8').write('\n'.join(str(v) for v in m.names.values())); m.export(format='ncnn', imgsz=224)"
```

### 4. 替换到工程

将 `model.ncnn.param/bin` 复制到 `app/src/main/assets/`，并按现有命名重命名（`yolo11m.ncnn.*` / `yolo11m-cls.ncnn.*`）。
如果改了文件名，需要同步修改 [MainActivity.java](app/src/main/java/com/example/yolo11ncnn/MainActivity.java) 中的资源名。

> 检测模型输出固定为 `(84, 8400)`（4 个框参数 + 80 类分数）；分类输出 1000 维且模型末尾已带 Softmax，解码代码无需修改。

## 正式版打包与签名

### 1. 生成签名证书（只需一次）

```powershell
keytool -genkeypair -v -keystore keystore\release.jks -alias yolo11 ^
  -keyalg RSA -keysize 2048 -validity 10000 ^
  -storepass <你的密码> -keypass <你的密码> ^
  -dname "CN=<姓名>, OU=Dev, O=<组织>, L=<城市>, ST=<省份>, C=CN"
```

### 2. 配置 `keystore.properties`（已被忽略，不会入库）

```properties
storeFile=keystore/release.jks
storePassword=<你的密码>
keyAlias=yolo11
keyPassword=<你的密码>
```

### 3. 打包并验证

```powershell
.\gradlew.bat clean assembleRelease
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk
```

`apksigner verify` 能正常输出证书 DN 即表示签名有效。

## 实测性能（Xiaomi 13 / Adreno 740 / CPU 4 线程）

| 任务 | 耗时 | 帧率 |
| --- | --- | --- |
| YOLO11m 实时检测 | 320–620 ms/帧 | 1.6–3.1 FPS |
| YOLO11m 视频逐帧检测 | 430–460 ms/帧 | 约 2 FPS |
| YOLO11m-cls 图片分类 | 41–59 ms/次 | — |

> 纯 CPU 下模型越大越慢；若需要更高帧率，可换回 yolo11n（约 10–12 FPS），或重新引入 Vulkan GPU 路径（见 `dist/` 中的历史 GPU 版 APK）。

## 常见问题排查

### 构建类

**1. AGP 报 `Your project path contains non-ASCII characters`**

项目路径含中文。`gradle.properties` 已加入 `android.overridePathCheck=true`；若仍失败，建议移动到纯英文路径。

**2. CMake 在中文路径下崩溃（0xC0000409）**

本工程不依赖 CMake。若自行改回 `externalNativeBuild` 会触发该问题，请继续使用 `build_native.ps1` 手工编译。

**3. `gradlew` 报找不到 Java / JDK 版本错误**

检查 `gradle.properties` 的 `org.gradle.java.home` 是否指向本机存在的 JBR；或删除该行使用系统 `JAVA_HOME`。

**4. NDK 未找到 / 版本不匹配**

确认 `%ANDROID_HOME%\ndk\28.2.13676358` 存在，并在 SDK Manager 中安装对应版本。

### 运行类

**5. 启动即崩溃：`dlopen failed: cannot locate symbol "__kmpc_fork_call"`**

缺少 OpenMP 运行时。本工程通过编译 `simpleomp.cpp` 提供这些符号，请确认 `build_native.ps1` 的源文件列表中包含它，并重新生成 `.so`。

**6. 使用系统 libomp 时 `SIGABRT`（`__kmp_affinity_initialize` 断言）**

NDK 28 的 libomp 在 Android 12+ 上会因 `sched_getaffinity` 失败中止，且 `KMP_AFFINITY=disabled` 等环境变量无法修复。**不要链接 libomp**，保留 `simpleomp.cpp`。

**7. 检测框标签显示为数字（如 `0 0.63`）**

`OverlayView` 未收到类别名称。检查检测模型加载完成后是否调用了 `overlayView.setLabels(detector.labels())`。

**8. 画面显示不全 / 上下或左右被裁掉**

`PreviewView` 默认 `FILL_CENTER` 会裁剪。布局中应使用 `app:scaleType="fitCenter"`，同时 `OverlayView` 使用 `Math.min` 映射，且 `Preview` 与分析流都设为 `RATIO_4_3`。

**9. 状态文字与系统状态栏（时间/电池）重叠**

`targetSdk 35+` 强制 edge-to-edge。需用 `WindowInsets` 为顶部栏叠加 `systemBars` 的 top inset，底部操作栏叠加 bottom inset。

**10. 检测标签互相压盖 / 贴边被截断**

`OverlayView` 已实现边界裁剪与标签互斥下移；若修改布局后复现，检查 `hudBounds` 是否同步、以及 `RectF.intersects` 避让逻辑是否被移除。

**11. 拍照分类结果离谱、图片横躺**

`ImageProxy.toBitmap()` 不会应用旋转。必须读取 `image.getImageInfo().getRotationDegrees()` 旋转；相册图片需通过 `ExifInterface` 读取 `TAG_ORIENTATION`。

**12. 相册/视频无法选择或提示无权限**

本工程使用系统 Photo Picker（`PickVisualMedia`），不需要存储权限。在 Java 中注意 `ImageOnly.INSTANCE` / `VideoOnly.INSTANCE` 的写法，漏掉 `.INSTANCE` 会编译失败。

**13. 切换摄像头后黑屏**

CameraX 要求重绑前调用 `unbindAll()`，并按 `Camera2CameraInfo.from(info).getCameraId()` 过滤；指定摄像头不存在时应回退 `DEFAULT_BACK_CAMERA`。

**14. 视频导入后没有画面**

可能是编码不被 `MediaMetadataRetriever` 支持，或视频为空。可换用 MP4(H.264) 测试；检测在主线程外执行，注意不要在主线程调用 `getFrameAtTime`。

**15. 安装失败 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`**

debug 与 release 签名不同。先卸载旧包：

```powershell
adb uninstall com.example.yolo11ncnn
adb install app\build\outputs\apk\release\app-release.apk
```

**16. 小米/红米提示 `INSTALL_FAILED_USER_RESTRICTED`**

需在开发者选项中开启「USB 安装」，解锁屏幕后重试；也可先在手机上确认安装弹窗。

**17. Gitee 提示有大于 50MB 的文件**

`yolo11m.ncnn.bin`（76.7MB）超过提示阈值但可正常推送。若需瘦身，可将模型迁移到 Git LFS 或发布到 Release 附件。

### 历史问题（Vulkan 版本）

**18. GPU 推理输出乱码（4e37 / inf）**

ncnn `20261020-android-vulkan` 在 Adreno 740 上输出异常，降回 `20250503` 可修复；当前版本已改为纯 CPU，不再涉及该问题。

## 已知限制

- 仅支持 `arm64-v8a`，未打包 32 位与 x86 ABI
- 纯 CPU 推理，YOLO11m 在手机上约 1.6–3.1 FPS
- 视频检测为抽帧离线处理（300ms/帧），并非实时逐帧播放
- 分类模型为 ImageNet 1000 类，不支持自定义类别（需自行导出并替换标签）
- 应用为单 Activity 竖屏布局，未适配平板横屏

## 版本与更新日志

详见 [CHANGELOG.md](CHANGELOG.md)。
