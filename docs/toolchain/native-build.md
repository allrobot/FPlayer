# T02 Native 构建执行说明

T02 只消费 `native-build/sources.lock.json`，首个 ABI 为 `arm64-v8a`，Android API
为 26，NDK 为 `29.0.14206865`。构建必须在 Ubuntu/Linux guest 中执行；Windows、
WSL 和相邻参考 checkout 不作为构建环境。

## 前置检查

执行账号需要能运行 `git`、`curl`、`python3`、Meson `1.11.2`、Ninja、
`pkg-config` 和 NDK r29 的 Clang/LLD。Meson 应安装在隔离 Python venv 中并将其
`bin` 目录临时加入 `PATH`，不得依赖发行版中较旧的 Meson。构建文件系统必须有
至少 35 GB 可用空间，并配置至少 4 GB swap；
建议 4 CPU/8 GB RAM 或更高。不要把 SSH、代理、账号或机器绝对路径写入仓库。

NDK 可通过 `ANDROID_NDK_ROOT` 指定；未指定时脚本从
`ANDROID_SDK_ROOT/ndk/29.0.14206865` 查找，并验证 `source.properties` 的精确
revision。输入、缓存和输出路径可以通过 `CACHE`、`WORK`、`OUT` 覆盖，但目标目录
必须不存在，脚本拒绝删除已有目录。

## 执行

```sh
cd FPlayer-Android
./native-build/build-arm64.sh
```

脚本会先离线运行 `verify-lock.py`，再准备锁定源码；Git 输入必须 checkout 到
完整 commit，归档必须通过 SHA-256。Meson 使用显式 Android cross file 和
`--wrap-mode=nodownload`，pkg-config 只查询目标 prefix。稳定的 cross file 位于
被忽略的 `native-build/android-arm64.cross`，编译器会把生成工作目录
映射为稳定前缀；安装后的开发期 `.pc`/`.la` 元数据也会规范化，证据收集会拒绝
仍嵌入工作目录的发布共享库。

输出默认位于 `native-build/out-arm64/`，包含 `include/`、`lib/` 和
`evidence/`。证据至少包括 source manifest、完整锁文件、toolchain manifest、
ELF `DT_NEEDED`/SONAME/16 KiB LOAD 对齐、SHA-256、第三方许可证原文和
`libc++_shared.so`。

## 可重复性

构建环境恢复后执行：

```sh
./native-build/verify-reproducible.sh
```

它在两个全新 `WORK`/`OUT` 目录中执行相同构建，比较 `include/` 和 `lib/` 的全部
文件哈希，并把通过的树哈希保存为 `evidence/reproducibility.sha256`。任何哈希
差异、缺少预期 `.so`、非 AArch64 ELF、低于 16 KiB 对齐、缺失 NOTICE 或额外未审计
动态依赖都会使构建失败；不要通过放宽校验来“完成”构建。
