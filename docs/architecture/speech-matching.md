# 跟读文本匹配设计

状态：草稿  
算法版本：`v1`

## 1. 目标

跟读匹配器负责回答两个问题：

1. 用户已经读到目标文本的什么位置？
2. 用户是否已经完成当前卡片的跟读？

它不负责：

- 评价发音是否标准
- 判断用户是否理解知识点
- 控制麦克风
- 保存学习记录
- 执行自动翻页

## 2. 输入与输出

```kotlin
enum class RecognitionSignal { PARTIAL, FINAL, SILENCE }

data class MatchInput(
    val targetText: String,
    val transcript: String,
    val previousProgress: MatchProgress?,
    val signal: RecognitionSignal
)

data class MatchProgress(
    val normalizedTarget: String,
    val matchedCharacterCount: Int,
    val targetCharacterCount: Int,
    val coverage: Double,
    val endingMatched: Boolean,
    val stable: Boolean,
    val completed: Boolean,
    val consecutiveCompletedResults: Int,
    val algorithmVersion: String
)

interface SpeechMatcher {
    fun match(input: MatchInput): MatchProgress
}
```

`coverage` 取值范围为 `0` 到 `1`。

示例：

```kotlin
MatchProgress(
    normalizedTarget = "物体保持原来运动状态不变的性质叫作惯性",
    matchedCharacterCount = 18,
    targetCharacterCount = 21,
    coverage = 0.857,
    endingMatched = false,
    stable = false,
    completed = false,
    consecutiveCompletedResults = 0,
    algorithmVersion = "v1",
)
```

## 3. 文本来源

匹配目标使用：

```kotlin
fun getTargetText(card: Card): String =
    card.speechText?.trim()?.takeIf { it.isNotEmpty() } ?: card.content.trim()
```

标题和快速记忆点不参与匹配。第一版固定使用普通话（`zh-CN`）。

对于公式、符号和特殊缩写，应在卡片中填写适合朗读的 `speechText`。

示例：

```json
{
  "title": "水的化学式",
  "content": "水的化学式是 H₂O。",
  "speechText": "水的化学式是 H 二 O。"
}
```

## 4. 文本标准化

目标文本和识别文本必须使用相同的标准化流程。

```kotlin
interface TextNormalizer {
    fun normalize(text: String, language: String): String
}
```

中文 `v1` 标准化步骤：

1. 转换为 Unicode 规范形式。
2. 转换全角字符为半角字符。
3. 英文字母统一为小写。
4. 删除空格、换行和制表符。
5. 删除不影响语义的标点。
6. 统一常见中文标点和英文标点。
7. 保留中文、数字和英文字母。
8. 应用有限的可配置替换规则。

示例：

```text
原文：  “惯性”，是物体保持运动状态的性质。
结果：  惯性是物体保持运动状态的性质
```

## 5. 标点处理

第一版忽略以下字符：

```text
， 。 ！ ？ 、 ： ； “ ” ‘ ’
, . ! ? : ; ' "
（ ） ( ) 【 】 [ ]
空格、换行、制表符
```

标点不参与覆盖率计算。

## 6. 数字和符号处理

第一版不尝试自动解决所有数字读法。

推荐由卡片作者填写明确的 `speechText`：

```json
{
  "content": "地球表面积约为 5.1 亿平方千米。",
  "speechText": "地球表面积约为五点一亿平方千米。"
}
```

第一版只提供少量稳定替换：

```text
％ → 百分之
°C → 摄氏度
= → 等于
+ → 加
- → 减
```

不能确定读法的内容不自动转换，例如：

- `2026`
- `3/4`
- `H₂O`
- 英文缩写
- 数学公式

卡片编辑底部弹层应提供“试听跟读文本”功能。

## 7. 匹配原则

使用按顺序匹配的字符序列算法。

允许：

- 识别结果中出现少量多余字符
- 用户重复部分内容
- 少量字符识别错误
- 用户短暂停顿后继续读

不允许：

- 只匹配几个关键词就完成
- 完全打乱目标内容顺序
- 只读开头后因静音而完成
- 只读结尾一句就完成
- 覆盖率下降导致已确认进度倒退

## 8. v1 匹配算法

第一版使用“顺序字符匹配”。

伪代码：

```kotlin
fun countOrderedMatches(target: String, transcript: String): Int {
    var targetIndex = 0
    for (character in transcript) {
        if (targetIndex >= target.length) break
        if (character == target[targetIndex]) targetIndex += 1
    }
    return targetIndex
}
```

覆盖率：

```kotlin
val coverage = if (targetCharacterCount > 0) {
    matchedCharacterCount.toDouble() / targetCharacterCount
} else {
    0.0
}
```

实际实现需要允许有限的识别错误。建议用序列对齐或编辑距离计算相似度，同时保留目标文本顺序。

为了降低误翻页风险，不能只使用编辑距离总分，还必须单独检查开头和结尾。

## 9. 已确认进度

语音识别器可能多次返回完整句子的不同版本：

```text
物体保持
物体保持原来的
物体保持原来的运动状态
```

也可能在后一次结果中修正前面的文字。

不能简单地把每次结果拼接起来，否则会造成重复：

```text
物体保持物体保持原来的物体保持原来的运动状态
```

匹配器分别计算每个识别快照，然后保存历史最佳进度：

```kotlin
val confirmedMatchedCount = maxOf(
    previousProgress?.matchedCharacterCount ?: 0,
    currentResult.matchedCharacterCount,
)
```

已经确认的进度不因临时识别结果波动而下降。

当识别会话重启时，可以保留确认进度，但新的文本只用于继续匹配尚未完成的部分。

## 10. 结尾匹配

只达到覆盖率还不够，必须确认用户读到了目标结尾。

定义结尾窗口：

```kotlin
fun getEndingWindow(targetLength: Int): Int = when {
    targetLength <= 8 -> 2
    targetLength <= 20 -> 3
    else -> 5
}
```

例如目标文本：

```text
物体保持原来运动状态不变的性质叫作惯性
```

长度大于 20 时，需要匹配结尾约 5 个字符：

```text
叫作惯性
```

结尾匹配仍允许一次小误差，但不能完全缺失。

## 11. 完成阈值

不同长度的卡片使用不同阈值：

| 标准化后长度 | 最低覆盖率 | 结尾要求 |
|---|---:|---|
| 1～5 个字符 | 100% | 必须匹配 |
| 6～15 个字符 | 95% | 必须匹配 |
| 16～40 个字符 | 90% | 必须匹配 |
| 41 个字符以上 | 88% | 必须匹配 |

```kotlin
fun getCoverageThreshold(length: Int): Double = when {
    length <= 5 -> 1.0
    length <= 15 -> 0.95
    length <= 40 -> 0.9
    else -> 0.88
}
```

这些值是初始参数，必须通过不同品牌和系统版本的 Android 真机测试调整。

## 12. 稳定性判断

临时识别结果可能瞬间达到阈值后又被系统修正，因此不能收到一次临时结果就立即翻页。

满足以下任意条件，结果视为稳定：

### 条件 A：最终结果

- 收到 `final_result`
- 覆盖率达到阈值
- 已匹配结尾

### 条件 B：连续临时结果

- 连续两次 `partial_result` 达到阈值
- 两次都匹配结尾
- 两次结果间隔至少 300 毫秒

### 条件 C：静音确认

- 当前最佳覆盖率达到阈值
- 已匹配结尾
- 用户停止说话约 800 毫秒

```kotlin
val completed = coverage >= threshold && endingMatched && stable
```

静音只能用来确认一个已经满足文本条件的结果，不能单独表示完成。

## 13. 短文本处理

短文本最容易被误触发。

对于 1～5 个字符的卡片：

- 必须完整匹配
- 必须收到最终结果或连续两次稳定结果
- 不能仅靠一次临时识别结果完成

例如目标文本为“惯性”，只识别到“惯”时不能完成。

空文本不能启动自动跟读。

## 14. 分段识别

长内容可能跨越多个语音识别会话。

运行时维护：

```kotlin
data class RecognitionAccumulator(
    val confirmedPrefixLength: Int,
    val currentTranscript: String,
    val restartCount: Int,
    val lastResultAt: Long?
)
```

识别会话结束但没有完成时：

1. 保留已经确认的匹配位置。
2. 不保存完整识别文本。
3. 重新启动识别。
4. 新识别结果从未完成部分继续匹配。
5. 最多自动重启三次。

超过限制后提示用户重新跟读或切换手动模式。

## 15. 完成判断伪代码

```text
标准化 targetText 和 transcript；目标为空时报告 EMPTY_TARGET_TEXT
对目标与识别文本做序列对齐，得到本次匹配字数
matchedCharacterCount = 历史已确认字数与本次匹配字数的最大值
coverage = matchedCharacterCount / 标准化目标长度（浮点除法）
threshold = 对应文本长度的覆盖率阈值
endingMatched = 结尾匹配结果
本次满足覆盖率与结尾条件时增加连续达标次数，否则归零
stable = 第 12 节定义的最终结果、连续临时结果或静音确认条件
短文本额外遵守第 13 节限制
completed = coverage >= threshold 且 endingMatched 且 stable
返回 MatchProgress，algorithmVersion = v1
```

以上流程用于表达规则，实际实现时需要将匹配结果和稳定性时间信息分开建模。

## 16. 隐私处理

完整识别文本只保存在内存中：

```text
语音识别
   ↓
临时 transcript
   ↓
匹配结果
   ↓
保存 coverage 和 endingMatched
   ↓
清除 transcript
```

以下位置不能记录完整跟读文本：

- SQLite
- 分析事件
- 崩溃报告
- 正式环境日志

## 17. 测试样例

目标：

```text
物体保持原来运动状态不变的性质叫作惯性
```

| 用户识别结果 | 预期 |
|---|---|
| `物体保持` | 不完成 |
| `物体保持原来的运动状态` | 不完成 |
| `物体保持原来运动状态不变的性质` | 不完成 |
| `物体保持原来运动状态不变的性质叫作惯性` | 完成 |
| `物体保持原来运动状态不变的特性叫作惯性` | 可完成 |
| `惯性` | 不完成 |
| 空字符串 | 不完成 |
| 完整结果重复两次 | 只完成一次 |

短文本目标：

```text
光合作用
```

| 用户识别结果 | 预期 |
|---|---|
| `光合` | 不完成 |
| `光合作` | 不完成 |
| `光合作用` | 稳定后完成 |
| `合作用` | 不完成 |

## 18. 测试指标

真机测试需要统计：

| 指标 | 含义 |
|---|---|
| 误翻页率 | 用户未读完但 App 已翻页 |
| 漏翻页率 | 用户读完但 App 没有翻页 |
| 完成延迟 | 用户读完到翻页的时间 |
| 重启次数 | 单张卡片重新启动识别的次数 |
| 识别失败率 | 无法产生可用结果的比例 |

第一版优先降低误翻页率。

初始体验目标：

- 误翻页率低于 2%
- 普通短句完成延迟低于 1.5 秒
- 正常环境下大多数卡片不需要用户手动重试

这些是产品测试目标，不保证在所有设备和噪声环境中都能达到。

## 19. v1 限制

第一版暂不处理：

- 发音评分
- 声调评分
- 多人声音区分
- 语义相似但文字完全不同的表达
- 复杂公式自动转读法
- 多语言混读优化
- AI 语义完成判断
