# 跟读文本匹配设计

状态：已确认  
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
    matchedCharacterCount = 16,
    targetCharacterCount = 19,
    coverage = 0.842,
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

1. 转换为 Unicode 规范形式（NFKC）。
2. 转换全角字符为半角字符。
3. 英文字母统一为小写。
4. 应用有限的可配置替换规则。
5. 删除空格、换行和制表符。
6. 删除不影响语义的标点。
7. 统一常见中文标点和英文标点。
8. 只保留中文、数字和英文字母。

替换必须排在删除标点**之前**：`%`、`=`、`+`、`-` 本身就是标点，先删就再也替换不出来；
另外 NFKC 已经把全角 `％`（U+FF05）折成半角 `%`，所以替换表的键是半角落法。
替换只做原文替换，不猜测读法，因此 `50%` 会变成 `50百分之` 而不是 `百分之五十`——
这类读法顺序差异由卡片作者用 `speechText` 解决，宁可漏翻页也不能误翻页。

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

该文本标准化后是 19 个字符，落在 `<= 20` 档，结尾窗口是 3：

```text
作惯性
```

长度超过 20 个字符时才使用 5 个字符的窗口。

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

## 20. 实现说明（节点 6）

代码位置：

```text
app/src/main/java/com/orange/echocards/domain/learning/matching/
├── MatchModels.kt        RecognitionSignal、MatchProgress、MatchInput
├── MatchingConfig.kt     阈值、窗口、静音与重启上限（唯一调参入口）
├── TextNormalizer.kt     ZhTextNormalizer
├── V1TextMatcher.kt      一次快照的顺序对齐结果
└── CompletionTracker.kt  已确认进度、稳定性与完成判定
```

与草案的差异，以及每条差异的原因：

1. **匹配与稳定性分开建模**。草案的 `SpeechMatcher.match(MatchInput)` 只能通过 `previousProgress` 看到上一次结果，而 300 毫秒间隔与 800 毫秒静音属于时间信息，`MatchProgress` 里放不下（第 15 节结尾也承认这一点）。实现拆成纯函数的 `TextMatcher`（不含时间）与有状态的 `CompletionTracker`（时间由引擎传入 `nowMs`），因此这些时间规则可以用普通单元测试覆盖，不需要虚拟时间。
2. **顺序匹配允许有限容错，但开头不允许跳字，短文本不给额度**。识别多一个字或漏一个字时，在 `maxAlignmentErrors = max(1, 目标长度 / 10)` 之内继续匹配，否则"不变的**特性**叫作惯性"这类正常识别会判为没读完（第 17 节要求它可完成）。两条限制：第一个目标字符必须精确匹配，否则"合作用"会被当成"光合作用"读完（第 8 节"单独检查开头"）；1～5 个字符的短文本容错额度为 0，因为第 13 节要求短文本完整匹配，漏一个字最容易被误判成读完。
3. **结尾只在识别结果的末尾找，并且在单次跟读内粘滞**。搜索范围是整个结果时，"的""性"这类常见字常常在句首就出现过，结尾检查会退化成永远为真；实现改为在结果末尾的 `窗口 + 2` 个字符内做顺序匹配（尾部余量容忍识别器在句尾多吐几个字）。另外识别器可能把最终结果截短，因此已匹配到的结尾在单次跟读内粘滞，否则读完却不翻页。粘滞只在重读、切卡、切模式和重置时清除。
4. **条件 B 的两次数值互不借用**。已确认进度是单调的（第 9 节），所以一次偶发达标的快照之后，累计覆盖率一直达标；若把这种"继承来的达标"也算进连续计数，之后随便识别到一个词就会翻页。因此条件 B 只统计**这一次快照从头匹配**就达标的次数。
5. **重启策略**。空最终结果、`NO_SPEECH`、`RECOGNITION_TIMEOUT`、`NETWORK_ERROR`、`INTERNAL_ERROR` 算一次会话结束，保留已确认进度并重启，最多 `maxSessionRestarts` 次；`PERMISSION_DENIED` 与 `RECOGNITION_UNAVAILABLE` 不重启，直接把原因交给用户。额度只计**没有新增确认进度**的会话：离线识别在每个自然停顿处都会结束一次会话（第 14 节的分段识别），长句子本来就会跨越多次会话，只要每段都读到了新内容就不该消耗额度，额度只用来拦住"完全识别不出东西"的情况。
6. **静音确认的计时在引擎**。引擎在每次快照和 `SpeechEnded` 之后重置计时，超时再调用 `onSilence()`；匹配器只判断"这个已经达标的文本能否被静音确认"。
7. **短文本规则**（第 13 节）映射为：不能用条件 C 完成，必须收到最终结果（条件 A）或两次间隔足够的部分结果（条件 B）。

第 11 节的阈值、第 10 节的窗口、第 12 节的静音与间隔、第 14 节的重启上限都在 `MatchingConfig` 里，是唯一的调参入口。

识别通道见 [ADR-002](../decisions/002-offline-vosk-recognition.md)：学习页使用 Vosk 端侧离线识别，它提供累计的部分结果与本地端点检测；`SpeechEnded` 在两种适配器里语义一致，都表示用户停下了。
