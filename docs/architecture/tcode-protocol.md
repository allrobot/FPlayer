# TCode 编码、版本和轴注册表

## 证据边界

T07 只读核对 `REF_TCODE_FIRMWARE` 的固定 checkout。参考实现的核心证据位置为
`firmware/src/TCode/v0.2/ToyComs.h`、`firmware/src/TCode/v0.3/TCode0_3.h`、
`firmware/src/TCode/v0.3/TCodeAxis0_3.h`、`firmware/lib/TCode/TCode.cpp` 和
`firmware/lib/constants.h`。产品仓库没有复制固件实现。

## 版本语义

| 版本 | 位置字段 | 位置范围 | interval 上限 | 轴命令结束 |
| --- | --- | --- | --- | --- |
| 0.2 | 3 位十进制 | 0..999 | 9,999 ms | LF |
| 0.3 | 4 位十进制 | 0..9,999 | 9,999,999 ms | LF |
| 0.4 | 4 位十进制 | 0..9,999 | 2,147,483,647 ms | LF 或 CR；本应用固定 LF |

内部位置仍为整数百分比 `0..100`。编码采用
`(percent * protocolMaximum + 50) / 100` 的半向上舍入，然后补齐固定位数。
`durationMs == 0` 生成无扩展的即时目标，正值生成 `I<durationMs>`。本阶段不生成
依赖当前设备位置的 `S` 速度扩展，也不生成 0.4 的 easing/gradient 扩展。

单帧可包含多个轴命令，以一个 ASCII 空格分隔，末尾恰好一个 LF。编码永远使用
US-ASCII，不带 NUL、CR 或额外空白。

## 轴注册表

0.2 注册 `L0`、`L1`、`L2`、`L3`、`R0`、`R1`、`R2`、`V0`、`V1`。
0.3 和 0.4 注册当前固件公开的 `L0`、`L1`、`L2`、`R0`、`R1`、`R2`、
`V0`..`V3`、`A0`..`A3`。协议解析器能表示的其他地址不会自动成为已知设备轴；
以后只能通过有证据和测试的注册表更新加入。

连接 profile 可声明版本。协商器发送逐字节固定的 `D1\n`，把完整返回交给
`TCodeVersion.parseIdentification`；只接受独立的 `TCode v0.2`、`v0.3` 或 `v0.4`
标识。声明版本与设备响应不一致、未知或含糊响应都不得静默降级。

## 拒绝语义

`TCodeProtocolException` 提供稳定 `TCodeErrorCode`。未知或版本不兼容轴、百分比越界、
负 duration、超过版本字段上限、空帧和不支持版本都在产生任何字节前拒绝。
轴限制、反转、最长 500 ms 时域和 generation 检查仍属于 T08 安全控制器；编码器不
用钳制隐藏上游错误。
