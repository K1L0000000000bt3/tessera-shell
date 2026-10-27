# Tessera
只是一个VibeCoding出来的无技术含量的项目，这个项目只约等于一个设计稿。

给 480×480 方屏安卓手表（Android 11）写的桌面（launcher），设计参考Windows Metro
整套界面用原生 Java + Canvas 自己画，不依赖 AndroidX / Gradle；所有尺寸都按 200×200 的设计坐标来算，再整体放大到屏幕。

## 有什么

- **表盘**：秒轨、磁贴、数据、大字、拼板五种。数据表盘可以放两张卡片（心率、活动、信息、日历，或者任意应用），没有卡片时变成大字布局，两者之间有变形动画。
- **长按换表盘**：Cover Flow 画廊（透视 + 倒影），可以拖动、快甩、点选，表冠一格切一张。
- **卡片**：在表盘上长按卡片拿起来换位置，拖出卡片区就拿掉；在开始屏长按磁贴、贴着屏幕上沿停一下，就能把它拖到表盘上变成卡片。
- **即时活动**：播放中的媒体、计时器这类进行中的通知会叠在卡片上面，横滑可以划走。
- **开始屏**：4 列磁贴，转门动画进出应用；从表盘上滑进来（表盘跟手上移，磁贴从屏幕左边缘翻进来）。
- **通知中心、横幅、设置 / 编辑页、搜索**。

## 目录

```
AndroidManifest.xml
src/com/tessera/shell/   原生代码
  NativeShell.java       导航和手势（表盘 / 开始屏 / 通知中心 / 页面）
  FaceView.java          表盘视图
  FaceStyles.java        各种表盘的绘制（只管画，动画进度由外面传进来）
  FaceCards.java         数据表盘的卡片区：顺序、拖动、即时活动、B ↔ C 变形
  FaceGallery.java       长按换表盘的 Cover Flow
  TileGridView.java      开始屏磁贴网格
  ShellData.java         应用、通知、卡片数据和设置
  Tween.java             CSS transition 语义的过渡（支持 delay）
res/                     窗口动画、样式、无障碍服务配置
web/                     早期 WebView 版的页面（还保留作为备用）
fonts/                   Inter 和 Noto Sans SC（300 / 400 / 600），原生绘制用
scripts/Build-Tessera.ps1  构建脚本
```

## 构建

需要：

- JDK 11 或更新（要有 `javac` 和 `jar`）
- Android SDK 里的 `build-tools\android-15` 和 `platforms\android-11`
- （可选）Tessera 设计稿目录，WebView 版页面用；没有也能编译，原生界面不受影响

```powershell
# 不签名，输出 build\tessera\aligned.apk
.\scripts\Build-Tessera.ps1 -Sdk D:\android-sdk

# 签名，输出 build\Tessera.apk
$env:TESSERA_KEYSTORE_PASS = '你的密钥密码'
.\scripts\Build-Tessera.ps1 -Sdk D:\android-sdk -KeyStore D:\keys\my.jks
```

安装后设为默认桌面：

```bash
adb install -r build/Tessera.apk
adb shell cmd role add-role-holder --user 0 android.app.role.HOME com.tessera.shell 0
```

通知、心率、日历、步数这些要在系统设置里给对应权限（通知使用权、身体传感器、日历、体能活动）。

## 调试入口

```bash
adb shell am start -n com.tessera.shell/.MainActivity --ei faceStyle 2          # 0 秒轨 / 1 磁贴 / 2 数据 / 3 大字 / 4 拼板
adb shell am start -n com.tessera.shell/.MainActivity --es debugTime 14:25:25   # 固定表盘时间，空串恢复
adb shell am start -n com.tessera.shell/.MainActivity --es faceCards heart,act  # 设表盘卡片
adb shell am start -n com.tessera.shell/.MainActivity --ef slow 10              # 所有动画放慢 10 倍，1 恢复
```

## 字体授权

`fonts/` 和 `web/noto/` 里的字体按 SIL Open Font License 1.1 分发：

- Inter — Copyright 2016 The Inter Project Authors，见 `fonts/Inter-LICENSE.txt`
- Noto Sans SC — Copyright 2014-2021 Adobe，见 `fonts/NotoSansSC-LICENSE.txt`
