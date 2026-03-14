# Evoke

Evoke 是一个 Android 应用容器，可将 APK 导入宿主控制的沙盒空间，并通过隔离的 stub 进程完成启动与运行。

## 项目功能

- 支持从已安装应用或本地 APK 导入到内部管理空间
- 通过宿主控制的 stub Activity 与进程槽位启动导入应用
- 支持 Intent 与 Deep Link 路由到已导入应用
- 持久化保存应用元数据、实例信息与权限授权状态
- 对包管理、Activity、广播、Content Provider、权限等部分能力进行运行时接管
- 使用 Native 层完成底层运行时、I/O 重定向，以及基于 Frida Gum 的 native hook 能力

## 模块结构

项目由三个核心模块组成：

- `app`：宿主应用、Jetpack Compose 界面、导航、运行时接收器、stub 组件与测试入口
- `core-virtual`：容器运行时、AIDL 服务、元数据解析、沙盒管理、Hook、持久化与应用生命周期编排
- `core-native`：JNI 与 C++ 层，负责 Native 引擎接入和 I/O 重定向

## 技术栈

- Kotlin 2.0
- Android Gradle Plugin 8.5
- Jetpack Compose + Material 3
- Hilt 依赖注入
- Room 本地持久化
- WorkManager 后台导入任务
- AIDL 进程内/进程间服务边界
- JNI + C++20 + CMake Native 支持
- Frida Gum native hook 支持
- Hidden API bypass 能力接入
- JUnit、AndroidX Test、Espresso、UI Automator 测试体系

## 环境要求

- Android Studio，JDK 17
- Android SDK 35
- 最低 Android 版本：Android 12（`minSdk 31`）
- 构建 `core-native` 需要 NDK 和 CMake

## 构建

```bash
./gradlew :app:assembleDebug
```

同时构建应用和仪器测试 APK：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

运行单元测试：

```bash
./gradlew :core-virtual:testDebugUnitTest
```

## 设备测试

安装并执行仪器测试：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.smartdone.vm.test/androidx.test.runner.AndroidJUnitRunner
```

## 目录说明

```text
app/            宿主应用与 Compose 界面
core-virtual/   容器运行时、Hook、AIDL 服务、持久化
core-native/    Native 运行时桥接与 I/O 重定向
```

## 当前状态

该项目当前主要聚焦于现代 Android 上的容器运行时能力，包括应用导入、进程引导、Intent 路由和宿主侧运行控制。
