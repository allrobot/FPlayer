# ADR-0002：项目许可证基线

- 状态：暂定接受，发布前复审
- 日期：2026-08-09

## 决策

FPlayer 自有源码以 `GPL-3.0-or-later` 发布。仓库根 `LICENSE` 包含 GPLv3 文本；源码文件逐步加入 SPDX 标识。MIT、BSD、Apache-2.0 等兼容依赖保留原始版权和 NOTICE。

## 原因

计划中的 native 媒体构建可能启用 GPL 组件，且参考生态包含 GPL 项目。采用 GPLv3-or-later 是保守的开源边界，也符合用户希望尽量兼容既有源码的方向。

## 限制

- 选择 GPL 不会自动让任意第三方源码变得可组合；每个依赖仍需单独核对版本、链接方式和许可证条款。
- mpv/libmpv 与 FFmpeg 的最终义务取决于具体 build configuration，不能只看仓库顶层 LICENSE。
- 只“参考设计/逻辑”与复制/改写代码不同。复制任何实现必须登记来源 commit、文件、修改和许可证。
- Android 应用商店分发、完整对应源码、构建脚本和安装信息义务在发布任务中复审。

## 发布前必须产出

- 直接/传递依赖许可证矩阵；
- native build manifest 和所有编译开关；
- `NOTICE`/`THIRD_PARTY_LICENSES`；
- 源码归属清单；
- 从发布 tag 重建二进制的说明。
