# Android 开发节点与测试

状态：执行清单；数据、卡片管理、JSON 导入、学习流程与跟读匹配开发中  
实现方式见[Android 第一版开发方案](../implementation-plan.md)。

## 开发节点与状态

每个节点交付可运行的 Debug 包，并通过对应测试后再进入依赖它的节点。测试编号用于开发记录和验收追踪。

每个节点的开发清单和测试用例放在对应文件中。`[ ]` 表示未完成，`[x]` 表示已完成；测试列使用“未执行／通过／失败／阻塞”。只有开发项全部完成且测试通过，才同步更新节点文件与下表状态。以下状态按当前 Android 代码、自动测试和模拟器检查结果维护；`dome.html` 原型本身不计入 Android 功能完成。

| 节点 | 当前状态 | 完成条件 |
|---|---|---|
| [1. 目标真机语音探针](01-voice-prototype.md) | 已完成 | 开发清单完成，V01–V05 通过 |
| [2. Room 与数据基础](02-data-foundation.md) | 进行中 | 开发清单完成，D01–D05 通过 |
| [3. 卡组和卡片管理](03-deck-card-management.md) | 进行中 | 开发清单完成，C01–C05 通过 |
| [4. JSON 导入与去重](04-json-import.md) | 进行中 | 开发清单完成，I01–I07 通过 |
| [5. 手动学习](05-manual-learning.md) | 进行中 | 开发清单完成，M01–M05 通过 |
| [6. 自动播放与自动跟读](06-automatic-learning.md) | 进行中 | 开发清单完成，A01–A07 通过 |
| [7. 集成验收](07-integration.md) | 未开始 | 开发清单完成，R01–R05 通过 |

## 测试执行方式

JVM 单元测试覆盖解析、指纹和 Learning Engine 的确定性规则；Room／设备测试覆盖数据库约束和事务；Compose 测试覆盖页面状态与用户操作。语音核心流程按节点 1 和节点 7 的用例在目标真机执行，并记录设备型号、Android 版本与结果。

在 `android/` 目录执行：

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

最后一条需要连接设备或启动模拟器；语音核心流程仍须单独在目标真机验收。
