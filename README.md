# Car Video — Android Auto 车机看 YouTube（研究用）

在 Android Auto 车机屏幕上开 `https://m.youtube.com`，可点选、滚动、播放、全屏，
**带车机端软键盘**，登录状态跟手机共用。

> 只给自己研究用，不上架 Play Store。没有任何停车 / 车速判断。

## 车机端按钮

ActionStrip 四个（`ACTIONS_CONSTRAINTS_NAVIGATION` 上限就是 4 个）：

| 按钮 | 作用 |
| --- | --- |
| **键盘** | 显示 / 收起车机软键盘 |
| **返回** | 退出全屏 → 收起键盘 → WebView 后退（按这个顺序） |
| **主页** | 回 m.youtube.com |
| **刷新** | 重新加载（在手机上登录完之后按这个） |

## 登录：在手机上登，车机自动是登录态

**系统输入法永远不会出现在 app 自己创建的 VirtualDisplay 上** —— 这就是车机上调不出
键盘的原因，跟代码无关。所以登录这件事换了个做法：

两个 WebView 跑在同一个 app 进程里，共用同一个 `CookieManager`。在手机上用真键盘登录
Google，车机端那个 WebView 就已经是登录状态了。手机端装好打开就是一个 YouTube 浏览器：

1. 手机打开 **Car Video**
2. 顶部状态条显示「⚠️ 未登录」就点 **登录**
3. 用手机键盘正常登录 Google
4. 状态条变成「✅ 已登录」
5. 车机端按 **刷新**

### 如果 Google 说「此浏览器或应用可能不安全」

Google 靠 User-Agent 里的 `; wv` 和 `Version/x.y` 两个标记识别嵌入式 WebView 并拒绝登录。
`WebKit.browserLikeUserAgent()` 已经把这两个标记去掉了，其余部分保持系统原样（这样
WebView 升级时 UA 会跟着更新）。

还是被拦的话，在 `Config.kt` 里填一个完整的桌面 Chrome UA：

```kotlin
const val FORCE_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
```

## 车机软键盘是怎么做的

`CarKeyboardView` 自己画出来的，按键按下去之后不是发 KeyEvent，而是往页面里注入 JS。
两个关键点：

- **按键全部设成不可获取焦点**（`isFocusable = false`，而且必须在 `setOnClickListener`
  之后设，因为那个方法会把 focusable 打开）。否则点一下按键，Android 焦点跑到按键上，
  WebView 失焦，页面里那个 `<input>` 就 blur 了 —— 正好是我们要输入的那个元素。
- **写值要走原生 setter**。YouTube 和 Google 登录页都是框架渲染的，直接 `el.value = x`
  它们收不到。注入的桥接脚本用
  `Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set` 来写，再派发
  `input` / `change` 事件。同时记住最后一个获得焦点的输入框，页面自己把焦点挪走了也还能找回来。

键盘高度是车机屏幕高度的 `Config.KEYBOARD_HEIGHT_FRACTION`（默认 46%），弹出时 WebView
会被压到上半部分而不是被盖住，这样正在输入的框不会被挡。

---

## 怎么装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK 路径：`app/build/outputs/apk/debug/app-debug.apk`（debug key 已签名，可直接安装）

装好后：

1. 手机上打开 **Car Video**（只有一行说明 + 一个地址输入框，画面不在手机上）
2. 跑 DHU（见下面「怎么在车机上跑起来」）或从 Play 内部分享安装
3. 车机屏幕里打开 **Car Video**
4. 顶部 ActionStrip 四个按钮：`键盘` / `返回` / `主页` / `刷新`

> ⚠️ **只是 sideload 安装的话，真车上永远看不到这个 app** —— 包括 Customise launcher 里。
> 这不是 manifest 写错了，是 Android Auto 的发布限制。详见下面。

---

## 为什么 sideload 之后车机上看不到（重要）

Android Auto 对 **Car App Library 模板应用** 和 **media 应用** 的处理完全不同。
Google 官方测试文档的原话：

> This setting applies to media, messaging notifications, and parked apps but
> **doesn't apply to apps built using the Android for Cars App Library**.
>
> — https://developer.android.com/training/cars/testing

也就是说，Android Auto 开发者设置里的 **Unknown sources 开关对这个 app 没用**。
它只对 media / messaging / parked 应用生效。

对照一下（左边是一个典型的 MediaBrowserService 媒体应用，右边是这个项目）：

| | 媒体应用（能显示） | Car Video（显示不了） |
| --- | --- | --- |
| 接入方式 | `MediaBrowserServiceCompat` | Car App Library `CarAppService` |
| `automotive_app_desc.xml` | `<uses name="media"/>` | `<uses name="template"/>` |
| 类别 | media | `androidx.car.app.category.NAVIGATION` |
| `androidx.car.app` 依赖 | 没有 | 有 |
| Unknown sources 管不管 | **管** | **不管** |

OsmAnd 也踩过同一个坑（[osmandapp/OsmAnd#15400](https://github.com/osmandapp/OsmAnd/issues/15400)）：
开了 Unknown sources 之后 sideload 的 VLC（media）出现了，但 OsmAnd（Car App Library 导航）
还是不出现。老办法 `pm install -i com.android.vending` 冒充 Play 安装来源在 Android 7 上有效，
Android 11+ 之后也失效了。

所以只有两条路：

| 路线 | 费用 | 能在真车里跑 | 说明 |
| --- | --- | --- | --- |
| **Desktop Head Unit（DHU）** | 免费 | ❌ 画面在电脑上 | 官方开发路径，真车的限制对 DHU 不生效 |
| **Play Console 内部应用分享** | US$25 一次性 | ✅ | 不是上架：没有审核、没有商店列表、不公开 |

---

## 怎么在车机上跑起来（DHU）

DHU 已经装好了：`$ANDROID_HOME/extras/google/auto/desktop-head-unit`（v2.0-linux）。
Ubuntu 不带 `libc++`，启动脚本直接借用系统里那个 NDK 的 host libc++，不用装任何包。

**手机上设置一次：**

1. 设置 → 已连接的设备 → **Android Auto**（找不到就在设置里搜）
2. 拉到最底，连点 **Version** 10 次 → 允许开发者设置
3. 右上角 **⋮ → Developer settings** → 打开 **Start head unit server**
4. 安卓系统设置 → **开发者选项** → 打开 **USB 调试**
5. USB 线插到这台 server，手机上点「允许」

**然后：**

```bash
deploy/dhu/run_dhu.sh
```

脚本会：检查设备已授权 → 检查 `my.kang.carvideo` 装了没 → `adb forward tcp:5277 tcp:5277`
→ 启动 DHU 窗口。任何一步不满足都会给出明确的提示。

**注意：DHU 是个 GUI 窗口，开在 `DISPLAY=:0`**，也就是这台 server 自己的屏幕上。
SSH 进来跑的话得人在机器前面，或者走 VNC / 远程桌面。

DHU 里常用快捷键：`?` 看帮助，鼠标点击 = 触屏，滚轮 = 旋钮。

---

## 怎么重新编译

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleDebug
```

`local.properties` 里已经写好 `sdk.dir`，所以 `ANDROID_HOME` 其实可以省。
需要 JDK 17；Gradle 由 wrapper 自动下载，第一次会慢一点。

其他有用的命令：

```bash
./gradlew clean assembleDebug    # 全量重编
./gradlew lintDebug              # 静态检查，报告在 app/build/reports/
./gradlew installDebug           # 编译 + 直接装到连着的手机
```

---

## 架构（为什么要这么绕）

车机那块屏幕给的是一个裸的 `Surface`，不是一个能塞 View 的 `ViewGroup`，所以：

```
Car host
  └─ NavigationTemplate            ← 只有 NAVIGATION 类别的 car app 才能拿到原始 Surface
       └─ SurfaceCallback
            ├─ onSurfaceAvailable(SurfaceContainer)
            │    ├─ DisplayManager.createVirtualDisplay(..., surface,
            │    │      VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            │    └─ android.app.Presentation(该 display)
            │         └─ 黑底 FrameLayout
            │              └─ 竖向 LinearLayout
            │                   ├─ webHost (weight 1)  WebView / YouTube 全屏 view
            │                   └─ CarKeyboardView     默认 GONE
            ├─ onClick(x, y)       → 合成 ACTION_DOWN + ACTION_UP
            ├─ onScroll(dx, dy)    → 合成一次拖动（中心 → 中心-distance）
            └─ onSurfaceDestroyed  → 释放全部，但记住模式 / 网址 / 播放进度
```

源码：

| 文件 | 作用 |
| --- | --- |
| `CarVideoService.kt` | `CarAppService`，`createHostValidator()` 返回 `ALLOW_ALL_HOSTS_VALIDATOR` |
| `CarVideoSession.kt` | `Session`，创建唯一的 Screen |
| `VideoScreen.kt` | `NavigationTemplate` + ActionStrip，注册 `SurfaceCallback` |
| `CarVideoRenderer.kt` | 核心：VirtualDisplay / Presentation / WebView / 键盘 / 触控转发 |
| `CarKeyboardView.kt` | 车机软键盘 |
| `WebKit.kt` | 两端共用的 WebView 配置、Cookie、UA、键盘注入脚本、全屏处理 |
| `Config.kt` | 所有可调常量 |
| `MainActivity.kt` | 手机端 YouTube 浏览器 + 登录 |

几个值得记住的点：

- **`minCarApiLevel` 必须是 5。** `SurfaceCallback.onClick(float, float)` 在
  androidx.car.app 里标了 `@RequiresCarApi(5)`（`onScroll` / `onFling` / `onScale` 只需要 2）。
  这是从 `app-1.7.0.aar` 的字节码里确认的，不是猜的。
- **`setMapActionStrip(Action.PAN)` 不是可选的。** 没有它 host 根本不会发 `onScroll`，
  WebView 就永远滚不动。
- **map action strip 里不能放带文字的按钮。** `ACTIONS_CONSTRAINTS_MAP` 的
  `maxCustomTitles` 是 0，放了会在 `setMapActionStrip()` 时直接抛异常。所以那里只有 `PAN`。
- **主 ActionStrip 最多 4 个按钮、最多 4 个自定义标题**（`ACTIONS_CONSTRAINTS_NAVIGATION`），
  现在用了 3 个。
- **`onScroll` 合成的拖动中间插了几个 `ACTION_MOVE`。** 只发一个 MOVE 经常过不了 WebView
  的 touch slop，页面就纹丝不动。
- **所有 `SurfaceCallback` 回调和 UI 操作都走主线程**（`CarVideoRenderer.onMain {}` 兜底）。
- YouTube 的全屏按钮走的是 `WebChromeClient.onShowCustomView`，不是 DOM 全屏，所以
  `onShowCustomView` / `onHideCustomView` 都实现了：全屏 view 塞进同一个容器，退出时恢复 WebView。

---

## 锁屏后画面定格（系统行为，不是 bug）

手机一锁屏，车机画面就停住，但声音还在 —— 原因在
[`VirtualDisplay.Callback.onPaused()`](https://developer.android.com/reference/android/hardware/display/VirtualDisplay.Callback)
的文档里写得很清楚：虚拟屏被系统暂停之后

> the surface will not receive any more buffers while paused

手机的 display group 进入睡眠时，系统会连带暂停这个 group 里的虚拟屏。画面因此定格，
而音频走的是完全独立的管线，所以继续播。

**没有公开 API 能把虚拟屏放进自己的 display group**（`VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP`
是 `@SystemApi`，需要 `ADD_TRUSTED_DISPLAY` 权限）。所以唯一的办法是不让手机睡着：

```kotlin
// Config.kt
const val KEEP_PHONE_AWAKE = true   // 默认开
```

开着的时候会给 Presentation 的 window 加 `FLAG_KEEP_SCREEN_ON`。代价是**投屏期间手机屏幕
一直亮着**。车里一般都插着充电，这个代价通常可以接受；不想要就改成 `false`，但那样锁屏
必定定格，没有第三种结果。

logcat 里能直接看到这个时刻：

```bash
adb logcat -s CarVideoRenderer:W
# virtual display PAUSED by the system - no more frames reach the car.
```

如果开了 `KEEP_PHONE_AWAKE` 还是看到这行，说明有别的东西（省电模式、三星的后台限制）
把手机按睡了。

---

## YouTube TV 模式（实验性）

`Config.kt` 里改一个值就能切：

```kotlin
val SITE = Site.TV      // 默认 Site.MOBILE
```

切过去之后自动生效三件事：加载 `youtube.com/tv`、UA 换成三星 Tizen 智能电视的、
输入面板默认显示**方向键**而不是键盘。

为什么值得试：

| | m.youtube.com | youtube.com/tv |
| --- | --- | --- |
| 为谁设计 | 手机触屏 | 电视 SoC，性能很弱 |
| 布局 | 竖屏优先 | 16:9 横屏，正好是车机的形状 |
| 操作 | 触摸（我们合成的点击） | 方向键（我们发真的 KeyEvent，比合成触摸可靠） |
| 登录 | Google 的网页表单，会拦 WebView | **配对码**，完全绕开那个拦截 |

那个登录方式是最大的好处：电视端用的是「在手机上打开 youtube.com/activate 输入码」，
根本不在 WebView 里填密码，所以 Google 那套「此浏览器可能不安全」的检测完全碰不到。

风险：需要伪装 UA，Google 可能随时改检测；TV 界面缩略图很多，不一定真的比手机版轻。
**先试，不行就改回 `Site.MOBILE`，一个常量的事。**

方向键面板：`▲ ◀ OK ▶ ▼`、`返回`、`播放/暂停`，还有一个「键盘」键切回 QWERTY 输入搜索词，
QWERTY 那边也有「方向键」键切回来。

---

## 播放卡顿的排查顺序

**先判断瓶颈在哪：如果 360p / 720p / 1080p 卡得一模一样，那就不是解码或带宽问题** ——
那两者的开销随分辨率上升，表现会有明显差别。都一样卡 = 瓶颈在显示 / 合成 / 投屏这条链路上。

### 代码侧已经做的（v3.0）

| 优化 | 为什么 |
| --- | --- |
| `FLAG_HARDWARE_ACCELERATED` 强制加到 Presentation 的 window 上 | Service context 创建的 Dialog 不一定会继承 manifest 里的 `android:hardwareAccelerated`，没有它整个 Presentation 走软件合成。硬件加速只能在 window 级别**打开**不能关闭，所以这么写是安全的 |
| `VIRTUAL_DISPLAY_FLAG_PRESENTATION` | 明确告诉系统这个虚拟屏是用来放 Presentation 的 |
| `setRequestedRefreshRate(60)`（Android 14+） | 固定这个虚拟屏的 vsync 节奏，Choreographer 按它给窗口排帧，而不是用默认值 |
| `setOffscreenPreRaster(true)` | 虚拟屏上的窗口可能被框架当成「不可见」而停止光栅化 tile |
| 去掉多余的不透明背景 | 原来每帧要填 4 层黑（window + root + column + webHost + WebView），现在只有 window 背景一层 |
| 去掉了外层多余的 FrameLayout | 少一层 view 和一次合成 |

装上新版后看一眼 logcat，这行会直接告诉你硬件加速本来是不是关着的：

```bash
adb logcat -s CarVideoRenderer:I
# CarVideoRenderer: surface=1920x720 render=1920x720 scale=1.0 dpi=160
#                   refreshRate=60.0 hwAccelWasAlreadyOn=false (now forced on)
```

`hwAccelWasAlreadyOn=false` 就说明之前确实在软件合成，这次的修改就是对症的。

### 还卡的话，调这个

`Config.kt` 里：

```kotlin
const val RENDER_SCALE = 0.8f   // 默认 1.0
```

光栅化和合成的开销跟**像素数**成正比，宽屏车机上这是每帧的主要成本 —— 这也正好解释了为什么
各个分辨率卡得一样。按 0.8 渲染再由 Surface 放大回去，像素数降到 64%。画面会软一点，但明显更顺。
触控坐标会自动跟着缩放，不会点偏。

### 无线 Android Auto：最有效的一项是 Android Auto 的「Video resolution」

无线 AA 的流程是：手机把**整个投屏画面**编码成视频 → 走 Wi-Fi → 车机解码。满屏动态视频
恰好是最吃码率的内容，而且瓶颈跟 YouTube 源的分辨率无关 —— 这解释了「什么分辨率都一样卡」。

Android Auto 开发者设置（版本号连点 10 次解锁）→ **Video resolution** → 改成
**Allow up to 1280x720**，还卡就改成 **800x480**。手机要渲染和编码的像素变少，Wi-Fi 要传的
数据变少，车机要解码的数据也变少，整条链路一起减负。代价是画面变软一些。
（来源：[HowToGeek](https://www.howtogeek.com/i-made-wireless-android-auto-much-smoother-with-one-hidden-developer-setting/)）

这个设置还会让车机给我们的 Surface 变小，所以本 app 的渲染开销也会跟着降，不用另外调 `RENDER_SCALE`。

### 从开源项目 AutoCarPlay 借鉴的改进（v6.0）

[AutoCarPlay](https://github.com/Shreyas4256/AutoCarPlay) 跟本项目架构完全一样（NAVIGATION +
VirtualDisplay + Presentation），并且在无线 Android Auto 上实测过。对照源码之后搬过来三处：

| | 之前 | 现在 |
| --- | --- | --- |
| 滚动 | 每个 `onScroll` 回调各做一套 DOWN→MOVE→UP，速度信息丢失，页面一顿一顿的，也没有惯性 | **一次拖动跨多个回调**：第一次 DOWN，之后每次 MOVE，停 150ms 才 UP。WebView 看到的是真实滑动，能惯性滚 |
| 触摸事件 | 简易 `MotionEvent.obtain(x, y)`，source 是 UNKNOWN | `TOOL_TYPE_FINGER` + `SOURCE_TOUCHSCREEN`，跟真手指一样；tap 的 UP 延迟 60ms 才发 |
| Surface 消失 | 整个拆掉重建，回来时 YouTube 重新加载、视频从头开始 | 只把 surface 从虚拟屏上摘下来，**WebView、播放进度、登录状态全部保留**，回来瞬间恢复 |

第三项带来一个行为变化：**切到 Google Maps 时 YouTube 的声音会继续播**（像听播客一样），
切回来画面在原处。手机保持常亮只在我们的画面确实显示在车机上时才生效，切到 Maps 就不再强制常亮。

另外：页面里 `intent://`、`vnd.youtube://` 这类跳转到别的 app 的链接现在会被直接忽略，
不会再出错误页。

### 拖动误触发点击（v7.0 修复）

有两个来源，都堵上了：

1. **回放的拖动本身被当成点击。** 拖动从屏幕中心开始回放；如果整个手势没超过 WebView 的
   touch slop（约 8dp），Chromium 看到的就是「中心按下、原地抬起」，也就是点了屏幕正中那个视频。
   现在 **DOWN 会一直压着不发，直到累计位移超过 slop**：要么移动得足够远、毫无疑问是滚动，
   要么什么都不发。回放出来的拖动不可能再变成点击。
2. **host 在拖动时顺带送了 `onClick`。** 拖动进行中、或刚结束 200ms 内收到的 click 一律丢弃
   （`CLICK_AFTER_DRAG_GUARD_MS`）。

顺带修了惯性滑动：以前拖动结束要静止 150ms 才抬手，速度追踪器看到手指「停住了」，
算出来的速度接近 0，惯性就没了。现在接上了 host 的 `onFling`，甩的时候**立刻抬手**，速度保留。

### 环境因素（这部分代码管不了，但影响可能比代码还大）

1. **无线 Android Auto 比有线卡得多。** 无线 AA 要把整个投屏画面编码后走 Wi-Fi 传，
   满屏动态视频正好是最吃带宽的内容，而且跟视频源的分辨率无关 —— 症状完全吻合。
   **先用一根质量好的 USB 数据线试一次**，这一步的差别通常最大。
2. **三星的省电策略。** 设置 → 电池 → 后台使用限制，确认 Car Video 不在受限名单里；
   关掉省电模式。Flip 7 是折叠机，长时间投屏发热后会降频，摸一下烫不烫。
3. **Android Auto 开发者设置里有「Video resolution」**，可以试着调低一档。
4. **很多车机本身就是 30fps 刷新的**，这个没法改。

---

## 排查

车机上黑屏先看 logcat：

```bash
adb logcat -s CarVideoRenderer:V CarApp:V AndroidRuntime:E
```

几种常见情况：

- **`could not show Presentation on the virtual display`** —
  `Presentation` 在某些 host 上从 Service context 起不来。logcat 里会带原始异常。
- **WebView 全黑但有声音** — 虚拟屏幕没拿到硬件加速。`android:hardwareAccelerated="true"`
  已经在 manifest 里，再确认车机端没开省电/软渲染。
- **点不动** — host 的 Car API level < 5，`onClick` 不会回调。
  `adb logcat | grep minCarApiLevel` 看有没有被 host 拒绝。
- **滚不动** — 确认 ActionStrip 上的 pan 按钮出现了；`SCROLL_GAIN` 调大试试。
- **键盘打字没反应** — 先点一下页面里的输入框让它获得焦点，再点「键盘」。
  还不行就看 logcat 里 `evaluateJavascript` 的返回值是不是 `"no-target"`，
  那说明页面里没找到可输入的元素。
- **登录被 Google 拒绝** — 见上面「如果 Google 说此浏览器或应用可能不安全」。

---

## 环境 / 版本

| 项 | 版本 |
| --- | --- |
| Gradle | 8.14.3（wrapper，`distributionSha256Sum` 已校验） |
| AGP | 8.13.2 |
| Kotlin | 2.2.20 |
| JDK | 17 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| androidx.car.app:app | 1.7.0 |
| androidx.activity | 1.13.0（activity-ktx，predictive back 需要） |
| Android SDK | `$HOME/Android/Sdk`（cmdline-tools 23.0, platform-tools 37.0.1, platforms/android-36, build-tools 35.0.0 + 37.0.0） |

SDK 是这次装的，licenses 已接受。`ANDROID_HOME=$HOME/Android/Sdk`。

想升到 AGP 9.x / compileSdk 37 也可以（仓库里有 `platforms/android-37.0`～`37.2`，
注意新版 SDK 的 platform 包名带上了小版本号），不过 8.13.2 + 36 这套是实际编译验证过的。

manifest 里除了 `INTERNET` / `NAVIGATION_TEMPLATES` / `ACCESS_SURFACE` 三个自己声明的权限，
合并后还会多出 `ACCESS_NETWORK_STATE` 等 —— 来自 car.app 的库 manifest，正常。

---

## 用自己的 nginx 分发 APK（可选）

想让手机直接从浏览器下载 APK，可以在 nginx 里加一个精确匹配的 location，直接指向
Gradle 的编译产物：

```nginx
location = /download_app {
    alias /path/to/this/repo/app/build/outputs/apk/debug/app-debug.apk;
    # .apk 不在 nginx 自带的 mime.types 里
    default_type application/vnd.android.package-archive;
    add_header Content-Disposition 'attachment; filename="carvideo-debug.apk"' always;
    add_header Cache-Control "no-store" always;   # 前面有 CDN 时不缓存旧包
}
```

几个容易踩的坑：

- **备份配置文件别放在 `sites-enabled/` 里。** 默认的 `include /etc/nginx/sites-enabled/*;`
  没有扩展名过滤，`.bak` 也会被当成配置加载，变成同一个域名的第二个 server 块。
- **`systemctl reload` 之后立刻 curl 可能拿到一次假 404。** reload 是优雅重载，旧 worker
  还会用旧配置再接一小会儿连接。等一两秒再验证。
- **确认 nginx 的 worker 用户能读到这个路径**，包括路径上每一级目录的执行权限。
- **这个地址默认谁都能下。** 需要的话加 `auth_basic`。

---

## 进真车：Play Console 内部应用分享（Internal app sharing）

这是 Car App Library 模板应用唯一能在真车里跑起来的办法。**不是上架**：没有审核、
没有商店列表、不公开、不用填隐私政策那一套。Play 只是给你一个私有安装链接，
手机从那个链接装完，Android Auto 就认为它「来自可信来源」，于是愿意加载它。

### 1. 打 release 包

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew assembleRelease bundleRelease
```

产物：

| 文件 | 用途 |
| --- | --- |
| `app/build/outputs/apk/release/app-release.apk` | 内部应用分享传这个最省事 |
| `app/build/outputs/bundle/release/app-release.aab` | 想走内部测试轨道（Internal testing）就传这个 |

签名用的是 `carvideo-release.keystore`，密码在 `signing.properties` 里。
两个文件都已经 gitignore，权限 600。

> **备份这两个文件。** 丢了之后同一个 Play 应用就再也发不了更新。

签名配置的取值顺序：`signing.properties` → `-P` 项目属性 →
环境变量 `CARVIDEO_RELEASE_STORE_PASSWORD` / `_KEY_ALIAS` / `_KEY_PASSWORD`。

`signing.properties` 的格式（这个文件不进 git，自己建）：

```properties
carvideoReleaseStoreFile=carvideo-release.keystore
carvideoReleaseStorePassword=...
carvideoReleaseKeyAlias=carvideo
carvideoReleaseKeyPassword=...
# Play Console 的包名绑定在你的应用上，必须一致；不填则默认 my.kang.carvideo
carvideoApplicationId=com.example.yourapp
```
一个都没配而又跑了 `*elease*` 任务，构建会直接报错，不会悄悄产出未签名的包。

### 2. 上传

1. Play Console → 左侧 **Test and release** → **App bundle explorer** 旁边的
   **Internal app sharing**（或直接开 https://play.google.com/console/internal-app-sharing）
2. 把 `app-release.apk` 拖进去
3. 复制生成的链接

内部应用分享不需要先创建应用、不需要填商店资料、不消耗版本号。

### 3. 手机上安装

1. 手机上**用 Chrome 打开那个链接**（必须登录上传用的那个 Google 账号，
   或者在 Play 商店设置里把该账号加成内部应用分享的授权用户）
2. 点安装 —— 会走 Play 商店的安装流程
3. 装完确认一下来源确实是 Play：
   ```bash
   adb shell pm list packages -i my.kang.carvideo
   # 期望看到 installer=com.android.vending
   ```
   如果显示的不是 `com.android.vending`，Android Auto 还是不会加载它。

### 4. 车机上

插上 Android Auto → **Customise launcher** 里应该就有 **Car Video** 了 → 加进 launcher。
如果没有，先强制停止 Android Auto 再重连（它会缓存 app 列表）。

### 为什么非得这么绕

因为这个 app 申请了 `androidx.car.app.ACCESS_SURFACE` —— 车机屏幕的原始 Surface，
拿到就能画任意内容，包括全屏视频。Google 把这个能力限制在可信来源安装的应用上。
media 应用（比如 UTBA 那个）只能往一个 Google 自己画的列表界面里塞条目，风险有上限，
所以才有 Unknown sources 这个后门。**让这个 app 能播视频的能力，正好就是让它没法
sideload 的原因。**

顺带一提：这个包别真的提交到生产轨道审核 —— 行驶中播放视频过不了 Play 的车载应用政策。
内部应用分享和内部测试轨道不走审核，自己研究用没问题。
