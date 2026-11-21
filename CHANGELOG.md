# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.1] - 2026-11-21

### 优化

- 检测与分类模型改为 ncnn **fp16 存储**（`ncnnoptimize ... 1`）：
  - 检测模型 `yolo11m.ncnn.bin`：76.7MB → **38.5MB**
  - 分类模型 `yolo11m-cls.ncnn.bin`：44.3MB → **22.2MB**
- Release APK 体积：112.9MB → **63.5MB**，满足 Gitee 附件 100MB 限制
- 精度影响可忽略（Python ncnn 对比：bus 检测 0.9316 → 0.9317，分类 Top-5 完全一致）
- 真机验证：Release 包实时检测 5 个目标正常，拍照分类 46ms

### 变更

- `versionName` 1.0.1，`versionCode` 3（与 1.0.0 同签名，可直接覆盖安装）

## [1.0.0] - 2026-11-21

首个正式版本。

### 新增

- **双模式工作流**：设置页互斥切换「检测」与「分类」，切换后功能入口实时更新，无状态残留。
- **检测模式**
  - 摄像头实时检测（YOLO11m，COCO 80 类）
  - 相册导入视频逐帧检测，画面叠加检测框，支持暂停 / 继续 / 停止
  - 状态栏显示帧率、单帧耗时与目标数量
- **分类模式**
  - 相册图片分类（YOLO11m-cls，ImageNet 1000 类，Top-5 + 置信度）
  - 摄像头拍照即时分类，自动处理拍照旋转与 EXIF 方向
- **摄像头选择**：自动枚举设备全部逻辑摄像头（前置/后置等），切换后自动重连并刷新预览；不可用时回退默认后置。
- **原生推理层**：新增 `yolo_cls` 分类推理；检测/分类共用同一 JNI 库，纯 CPU 静态链接 ncnn 20250503。
- **UI/交互**
  - 自适应应用图标（Android 8+ 遮罩适配）
  - 全面屏/刘海屏 inset 适配，状态面板不再压住系统状态栏
  - 检测标签边界裁剪、HUD 避让与标签互斥防重叠
  - 预览改为 FIT_CENTER 完整显示，不再裁掉约三分之一的视野
- **工程化**
  - `build_native.ps1` 一键编译原生库
  - 签名配置（`keystore.properties`，不入库）与 Release 打包流程
  - README 环境依赖、复现指南与故障排查文档

### 修复

- 修复 PowerShell 写入 Java 文件带 BOM 导致编译失败、Gradle 复用旧 APK 的问题
- 修复 libomp 在 Android 12+ 上 `__kmp_affinity_initialize` 断言崩溃（改用 `simpleomp.cpp`）
- 修复 Vulkan 版 ncnn 在 Adreno 740 输出乱码的问题（改为纯 CPU 并锁定 20250503）
- 修复检测结果 `attr_major` 判断写反导致框位置全错的问题
- 修复检测标签显示为数字而非类别名的问题
- 修复拍照/相册图片方向错误导致分类结果异常的问题
- 修复深色设置页下拉框文字对比度过低的问题

### 已知限制

- 仅支持 `arm64-v8a`，Android 7.0（API 24）及以上
- 纯 CPU 推理：YOLO11m 实时检测约 1.6–3.1 FPS，视频检测约 2 FPS；分类约 41–59 ms/次
- 视频检测为 300ms 抽帧处理，非实时播放
- 分类模型为通用 ImageNet 1000 类，自定义类别需自行导出并替换标签

### 构建信息

| 项目 | 值 |
| --- | --- |
| versionName | 1.0.0 |
| versionCode | 2 |
| compileSdk / targetSdk | 36 |
| minSdk | 24 |
| ABI | arm64-v8a |
| 模型 | YOLO11m（检测）+ YOLO11m-cls（分类） |
| 推理后端 | ncnn 20250503 CPU |

Release 产物：`YOLO11-dual-mode-v1.0.0-release.apk`

[1.0.0]: https://gitee.com/open-zynq/yoloandroid/releases/tag/v1.0.0
[1.0.1]: https://gitee.com/open-zynq/yoloandroid/releases/tag/v1.0.1
