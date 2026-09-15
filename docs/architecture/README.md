# 知声卡技术架构

状态：草稿

## 1. 架构目标

知声卡需要同时运行在 Android 和 iPhone，并支持：

- 卡片浏览和编辑
- 手动滑动学习
- 系统语音朗读
- 用户跟读识别
- 读完后自动翻页
- 本地数据保存
- 语音功能不可用时继续手动学习

第一版采用本地优先设计，不依赖业务后端。

## 2. 技术栈

| 范围 | 技术 |
|---|---|
| 应用框架 | React Native |
| 开发语言 | TypeScript |
| 工程工具 | Expo Development Build |
| 路由 | Expo Router |
| 状态管理 | Zustand |
| 本地数据库 | SQLite |
| 手势 | React Native Gesture Handler |
| 动画 | React Native Reanimated |
| Android 原生代码 | Kotlin |
| iOS 原生代码 | Swift |

具体依赖版本在创建工程时锁定。

## 3. 系统上下文

```mermaid
flowchart LR
    User[学习者]
    App[知声卡 App]
    TTS[系统语音朗读]
    ASR[系统语音识别]
    DB[(本地 SQLite)]

    User -->|创建和学习卡片| App
    App -->|朗读文本| TTS
    TTS -->|播放完成事件| App
    User -->|跟读语音| ASR
    ASR -->|识别文字| App
    App <--> DB
