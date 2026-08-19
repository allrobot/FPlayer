# Funscript 解析与匹配

本文固定 P2/T04 的输入边界。实现入口为 `core/script/`，后续同步器只消费已校验的
`ScriptBundle`，不得再次排序、钳制或修复输入。

## 严格解析

- 输入必须是合法 UTF-8 JSON object；未知元数据字段允许保留在源文件中但不参与调度。
- 根 `actions` 映射 `L0`。根 `actions` 可以缺省，但此时必须存在非空 `axes`。
- `actions[].at` 必须是 JSON 整数字面量且不小于 0；`actions[].pos` 必须是 JSON
  整数字面量且位于 0 到 100（含端点）。字符串和小数不做隐式转换。
- 每个轴至少有一个 action，时间戳必须严格递增，任意位置的重复时间戳均拒绝。
- 嵌入轴只接受 `L0`、`L1`、`L2`、`R0`、`R1`、`R2`、`V0`、`V1`、
  `A0`、`A1`、`A2` 的精确大写 ID。
- 重复轴、根 `L0` 与嵌入 `L0` 并存、空 actions 和空 axes 均拒绝。
- 失败通过 `ScriptParseException` 返回稳定错误代码、来源名和 JSON path；错误消息不回显
  脚本内容。

## 文件名匹配

文件扩展名和 basename 比较不区分大小写，大小写转换固定使用 `Locale.ROOT`。只识别
`.funscript`，只剥离最后一个已知轴后缀：

| 轴 | 规范后缀 | 别名（按优先级） |
| --- | --- | --- |
| `L0` | `L0` | `stroke`, `up` |
| `L1` | `L1` | `surge`, `forward` |
| `L2` | `L2` | `sway`, `left` |
| `R0` | `R0` | `twist`, `yaw` |
| `R1` | `R1` | `roll` |
| `R2` | `R2` | `pitch` |
| `V0` | `V0` | `vib` |
| `V1` | `V1` | `pump` |
| `A0` | `A0` | `valve` |
| `A1` | `A1` | `suck` |
| `A2` | `A2` | `lube` |

无轴后缀的精确同名脚本保留其全部嵌入轨道；带轴后缀的文件必须只含一个根轨道，随后
重映射到后缀轴。媒体 basename 本身以已知别名结尾时，若剥离后不能匹配媒体，则退回
完整 basename 的无后缀匹配。

同一轴存在多个候选时，选择顺序为：规范轴 ID、别名表顺序、无后缀脚本；同级再按
`Locale.ROOT` 规范化文件名、原文件名和稳定 locator 排序。结果始终附带
`AMBIGUOUS_AXIS` 诊断及所有被舍弃 locator，不静默覆盖。带轴后缀的多轴内容被拒绝并
返回 `INCOMPATIBLE_AXIS_CONTENT`。
