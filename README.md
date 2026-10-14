# 微喵歌词同步（wemew.lrc.sync）

Android 歌词同步终端：读取手机上正在播放的音乐（标准 `MediaSession`，Android 4.4 走
`RemoteController`，兜底解析播放器通知），跨乐库匹配歌词，然后通过内嵌的
**局域网 Web 服务**把带逐字时间轴的歌词实时推送给车机 / 平板 / 电脑浏览器。

App 本身不再绘制任何歌词界面——显示端全部是 Web。

## 功能

- **歌词获取**：网易（LRC/YRC 逐字）、QQ（QRC 逐字）、酷狗（KRC 逐字）、酷我、汽水
  （原生逐字）、本地 `.lrc` 与内嵌标签（FLAC/MP3/M4A/OGG）；自动按播放器选词库并回退，
  匹配结果与逐字时间轴本地缓存。
- **Web 同步服务**：默认常开，端口 8765（占用时顺延至 8774）。WebSocket 推送
  `{track, state, line, pong}`，REST 提供降级快照，`/cover` 提供封面。协议见
  **[docs/web-protocol.md](docs/web-protocol.md)**（对外契约，含时间同步模型与兼容规则）。
- **门店授权**：采用微喵设备身份绑定——App 生成稳定 12 位设备码，门店在微喵后台登记
  后，App 每 30 秒轮询拿到门店名/ID 并显示在首页。
- **单页状态界面**：门店授权 · 同步状态（地址与在线显示端）· 歌词信息（逐字时间轴
  覆盖率）· 权限。
- **测试显示端**：仓库 [`web-test-client/`](web-test-client) —— 逐字高亮参考客户端
  （支持 `?host=` 直连手机、字号/颜色/字体调节、mock 服务器离线调试）。

## 使用

1. 安装 App，授予「通知使用权」（音乐读取权限）。
2. 复制首页「同步状态」里的地址（如 `http://192.168.1.5:8765`）。
3. 在同局域网的显示设备浏览器打开 `web-test-client/index.html?host=192.168.1.5:8765`，
   播放音乐即可看到逐字歌词。
4. 门店授权：把首页显示的设备码登记到微喵门店后台，绑定状态自动刷新。

## 架构

```text
MusicNotificationListener（通知监听，授权入口）
  → MusicSessionReader（MediaSession / RemoteController / 车机 AIDL / 通知兜底）
  → MusicStateStore（会话仲裁、位置插值、歌词加载调度）
  → MultiSourceLyricClient（本地 + 五家在线词库，QRC/KRC 解码）
  → LrcTimeline（LRC/YRC/QRC/KRC 统一时间轴，含逐字）
       ↓ WebStatePublisher（500ms 差分：换曲推 track、~1s 推 state 锚点）
WebSyncService（specialUse 前台服务，常开，含看门狗自愈）
  ├─ WebDisplayServer（NanoWSD）：/ws · /api/state · /api/track · /cover
  ├─ StoreBindingClient（设备码 + 门店绑定轮询）
  └─ 通知栏显示服务地址
```

歌词链路继承自 `zuo-qirun` 的多乐库实现并精简为纯采集；显示端协议对齐 nbox 的
`{type,data}` 信封模式（连接即推快照、事件驱动广播、2s 自动重连）。

## 构建

```
./gradlew testDebugUnitTest lintDebug assembleDebug    # JDK 17+（Gradle 8.9 需 ≤ Java 21）
```

`minSdk 19`（Android 4.4），依赖刻意保持极简（AppCompat、Material、NanoHTTPD 2.3.1）。
版本号由 CI 注入（`APP_VERSION_CODE/NAME`），发布由 GitHub Actions 签名打包。

## 仓库结构

| 目录 | 说明 |
|---|---|
| `app/src/main/java/wemew/lrc/sync/` | Android 端全部源码（单包平铺） |
| `web-test-client/` | Web 参考客户端 + mock 服务器 |
| `docs/web-protocol.md` | Web 同步协议规范 v1 |
| `.github/workflows/build-release.yml` | 测试 / Lint / 签名发布流水线 |
