# 需要人工处理的工具/环境

本文件只列 AI 难以安全独立完成、或需要用户 GUI/宿主机操作的项目。能通过命令行自动下载的 Gradle、Maven 依赖、Android command-line tools、NDK、Meson、Ninja、Git 包等不列为人工下载项。

## 1. 升级 Android Studio

当前安装是 2021.1.1，不能可靠导入 AGP 9.3/Gradle 9.5 工程。命令行构建不受影响，但需要 IDE 编辑、Compose Preview、Profiler 或 Layout Inspector 前，应人工安装支持 AGP 9.3 的当前稳定 Android Studio，建议与旧版并存后再迁移设置。

官方下载：[Android Studio](https://developer.android.com/studio)

AI 不应静默替换正在使用的桌面 IDE、迁移插件或删除旧配置，因此把它保留为人工步骤。

## 2. 扩容 Linux native 构建虚拟机

`LINUX_BUILDER` 当前根磁盘容量较小，剩余空间不足以稳妥容纳 NDK、多个 native 依赖、调试符号和多 ABI 中间产物。开始 T02 前建议：

- 虚拟磁盘总容量至少 60 GB；
- 根分区可用空间至少 35 GB；
- 配置 4~8 GB swap，避免 8 GB RAM 下链接阶段被 OOM kill；
- 扩容前创建可恢复快照或备份。

VirtualBox 虚拟磁盘扩容、分区/文件系统扩展涉及关机和宿主机 GUI/磁盘布局，AI 不应在未确认具体虚拟磁盘文件和恢复点时自行执行。

## 当前不需要用户下载

- JDK 17 已存在；构建脚本会显式选择，不依赖错误的全局 `JAVA_HOME`。
- Android SDK 37 / Build Tools 36.0.0 已存在。
- NDK r29 当前进程尚未发现，但可在 T02 通过命令行定位或安装，无需现在人工处理。
- Ubuntu 内的 Git、Python、Meson、Ninja、CMake、pkg-config 和编译工具可由 AI 在 T02 通过包管理器安装。
