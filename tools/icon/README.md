# 启动图标资源

知声卡的应用图标：品牌青绿渐变底 + 白色卡片 + 三道声波，表示“卡片在发声”。

- 自适应图标（API 26+）：`android/app/src/main/res/drawable/ic_launcher_background.xml`、
  `ic_launcher_foreground.xml`、`ic_launcher_monochrome.xml`（Android 13+ 主题图标用），
  由 `mipmap-anydpi-v26/ic_launcher{,_round}.xml` 组合。
- 传统位图（minSdk 24，API 24/25 用）：`mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher{,_round}.png`。

两者共用同一套 108x108 画布坐标（启动器只显示中心 72x72，内容需落在中心 66dp 安全区内），
改图标时请同时更新矢量 XML 和 `IconGenerator.java`，再重新生成位图：

```bash
java tools/icon/IconGenerator.java android/app/src/main/res tools/icon/preview/contact-sheet.png
```

生成后打开 `preview/contact-sheet.png` 检查圆形/方圆遮罩效果、各密度清晰度和单色层轮廓。
