# 知声卡文档

## 推荐阅读顺序

具体实现见 [Android 第一版开发方案](implementation-plan.md)；任务状态和验收用例见 [开发节点与测试](development/README.md)。

1. [第一版需求](product/requirements.md)
2. [页面与交互](design/screens.md)
3. [技术架构](architecture/README.md)
4. [数据模型](reference/data-model.md)
5. [JSON 导入格式](reference/import-format.md)
6. [Learning Engine](architecture/learning-engine.md)
7. [语音接口](reference/speech-api.md)
8. [跟读匹配](architecture/speech-matching.md)

## 文档状态

- 草稿：仍在讨论
- 已确认：可以据此开发
- 已实现：已经通过代码和测试验证

## 架构决策

- [为什么仅开发 Android 原生 App](decisions/001-use-native-android.md)
- [跟读识别改用 Vosk 端侧离线模型](decisions/002-offline-vosk-recognition.md)
