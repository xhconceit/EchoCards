# ADR-002：跟读识别改用 Vosk 端侧离线模型

状态：已确认  
日期：2026-09-20  
决策人：项目团队  
影响范围：节点 6（自动跟读）

## 1. 背景

[ADR-001](001-use-native-android.md) 决定第一版使用 Android 系统语音服务（`TextToSpeech` + `SpeechRecognizer`）。节点 1 在目标真机（24122RKC7C / HyperOS / Android 16）验证后，发现系统识别通道有三个与自动跟读直接冲突的约束：

- 设备上的识别服务是**在线**的（`isOnDeviceRecognitionAvailable` 为 false），实测会在网络抖动时报 `ERROR_NETWORK_TIMEOUT`，跟读随时可能失败。
- 厂商引擎对 `EXTRA_PARTIAL_RESULTS` 的支持与返回形态不受控，而自动跟读的覆盖率判断需要稳定、及时的部分结果。
- 端点、静音与错误码行为由厂商决定，无法按跟读匹配的需要调整。

节点 6 需要"朗读完成 → 收音 → 逐字判断覆盖率与结尾 → 读完才翻页"，这对识别通道的要求比"能识别出文字"更高。

## 2. 决策

**学习页的自动跟读使用 Vosk 端侧离线识别模型（`speech/vosk/`），朗读继续使用 Android 系统 `TextToSpeech`。**

- 模型资源随 APK 分发（`app/src/main/assets/model-cn/`，约 62 MB），首次使用时解压到 `filesDir/model-cn/` 并加载。
- 学习页通过 `AndroidSpeechServices.createWithOfflineRecognition()` 组装：系统 TTS + Vosk 识别 + 现有能力与音频焦点适配器。
- 语音探针页保留系统识别器、Vosk、SenseVoice 三种实现，继续用于设备能力对比与排查。
- 领域接口（`SpeechRecognizer`、事件与 `operationId` 规则）不变，Learning Engine 不感知具体实现。

## 3. 选择离线识别的理由

- **真实的部分结果**：Vosk 在同一句话内持续给出累计识别文本，跟读匹配可以边读边判断覆盖率。
- **可控的端点**：`acceptWaveForm` 的端点检测由本地模型决定，配合引擎的静音确认（`silenceConfirmMs`）形成稳定的完成判定。
- **不依赖网络与厂商服务**：不受在线识别超时、服务缺失、被系统省电策略限制的影响。
- **隐私**：语音数据不出设备，识别文本只存在于内存中，符合[跟读匹配设计](../architecture/speech-matching.md)第 16 节。

## 4. 不采用的方案

### 继续只用系统 `SpeechRecognizer`

不采用。目标是设备上走在线识别，部分结果与错误行为不受控，跟读完成判定无法保证。

### SenseVoice 云端识别

不采用作为跟读通道。它是批量接口，没有部分结果，必须等用户说完再上传，端到端延迟与隐私都不符合跟读场景；保留在探针页用于对比。

### 两种识别通道在设置里可切换

暂不采用。第一版只保留一条跟读路径，避免两套时序与失败恢复都要验证。若离线精度在部分设备上不可接受，再评估加入开关。

## 5. 影响与代价

| 项 | 影响 |
|---|---|
| APK 体积 | 增加约 62 MB 模型资源 |
| 首次使用 | 首次跟读要把模型解压到 `filesDir` 再加载；进入自动跟读时后台预热，与首张卡片朗读并行 |
| 识别精度 | 离线小模型的中文精度弱于在线服务；匹配阈值是唯一调参入口（`MatchingConfig`），需要真机调优 |
| 验收 | 语音核心流程仍按节点 1 与节点 7 的要求在目标真机执行，不能只用模拟器 |

## 6. 与 ADR-001 的关系

ADR-001 中"识别使用 Android `SpeechRecognizer`"的部分由本决策取代；其余结论（Kotlin + Compose、单 Activity、ViewModel + StateFlow、Room、系统 `TextToSpeech`、语音回调统一转换为带 `operationId` 的领域事件）继续有效。
