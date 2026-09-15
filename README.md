# 知声卡 EchoCards


一个通过知识卡片、语音朗读和用户跟读进行学习的 App。

## 支持平台

- Android
- iPhone

## 核心功能

### 手动学习

每张卡片展示一个知识点，用户滑动切换卡片，
也可以点击按钮播放朗读。

### 自动跟读

1. App 朗读当前卡片。
2. 朗读结束后，提示用户跟读。
3. 识别用户跟读内容，判断是否读完。
4. 读完后自动切换到下一张。
5. 用户可以随时暂停、重读或跳过。

## 第一版范围

- 卡组管理
- 卡片新增、编辑和删除
- 手动滑动学习
- 自动朗读与跟读
- 本地保存内容和学习进度
- 普通话支持

## 后续功能

- 账号与跨设备同步
- 更多语言
- 复习计划

## 技术方向

- React Native + TypeScript
- Expo Development Build
- SQLite 本地存储
- Android / iOS 语音能力适配

## 文档入口

后续在 docs/README.md 中维护。

## 本地开发

自动跟读使用原生语音识别模块，因此不能在 Expo Go 中运行。首次运行或原生依赖发生变化后，需要重新生成并安装 Development Build：

```bash
npm install

# iOS 模拟器
npm run ios

# Android 模拟器
npm run android
```

真机通过 USB 连接后使用：

```bash
npm run ios:device
# 或
npm run android:device
```

Development Build 已安装后，日常只需启动 Metro：

```bash
npm start
```
