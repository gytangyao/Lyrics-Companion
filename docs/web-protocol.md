# 微喵歌词同步 · Web 协议规范（v1）

手机端 App（`wemew.lrc.sync`）内嵌一个 HTTP + WebSocket 服务端，向局域网内任意浏览器
显示端推送歌词状态。**本协议是对外契约**：仓库中的 `web-test-client/` 只是参考实现，
未来任何通用显示端都应按本文档实现。

- 传输：单端口，默认 **8765**（被占用时顺延 8766–8774，实际端口见 App 通知与欢迎帧后的 URL）
- 安全模型：v1 面向局域网，无鉴权、无 TLS（`http://` / `ws://`）
- 参考实现：服务端 `app/src/main/java/wemew/lrc/sync/WebProtocolCodec.java`（与本文档同步演进）

## 1. 兼容性规则（双方必须遵守）

1. **未知消息类型必须忽略**，不得断开连接。
2. **未知字段必须忽略**，不得报错。
3. 信封缺少 `v` 视为 `v: 1`；收到高于自己版本的 `v` 时，按自己理解的字段尽力渲染。
4. 新版本只增不改：字段与类型名一经发布即冻结，扩展只新增。

## 2. 信封（双向一致）

```json
{ "v": 1, "type": "track", "ts": 1760000000000, "data": { … } }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `v` | int | 发送方协议版本，当前 `1` |
| `type` | string | 稳定的 snake_case 消息名 |
| `ts` | long | 发送方墙钟毫秒（服务器→客户端帧必带） |
| `data` | object | 各类型的载荷 |

## 3. 连接握手

客户端连上 `/ws` 后，**服务器立即推送** `welcome` + 当前快照（`track` + `state`）。
客户端应当（非必须）发送 `hello`；静默客户端按 v1 服务。

```json
// 客户端 → 服务器
{"v":1,"type":"hello","data":{"protocol":1,"client":{"name":"wemew-test-page","version":"1.0"}}}

// 服务器 → 客户端
{"v":1,"type":"welcome","ts":1760000000000,"data":{
  "protocol":1,
  "server":{"name":"wemew-lrc-sync","version":"<App 版本号>"},
  "capabilities":["track","state","line","cover","ping"]
}}
```

`capabilities` 是前向扩展位：未来新增能力（如 `command`、`style`）会出现在数组里，
客户端按需选用，未见过的能力忽略。

## 4. 服务器 → 客户端消息

### 4.1 `track` — 全量曲目与时间轴

曲目身份、时间轴或歌词模式**任一变化**时推送；连接时也作为快照推送。

```json
{"v":1,"type":"track","ts":1760000000000,"data":{
  "active": true,
  "playing": true,
  "title": "稻香",
  "artist": "周杰伦",
  "source": "netease",
  "sourceName": "网易云音乐",
  "durationMs": 223000,
  "positionMs": 41200,
  "speed": 1.0,
  "offsetMs": 0,
  "lyric": {
    "live": false,
    "sourceName": "网易云歌词",
    "lines": [
      {"t": 40100, "d": 5200, "text": "对这个世界如果你有太多的抱怨",
       "translation": "If you have too many complaints about this world",
       "words": [
         {"t": 40100, "d": 410, "text": "对"},
         {"t": 40510, "d": 410, "text": "这"}
       ]}
    ]
  },
  "hasArt": true,
  "artVersion": 7
}}
```

| 字段 | 说明 |
|---|---|
| `active` | 是否有活动播放会话 |
| `positionMs` + 信封 `ts` | 时间锚点（配合 `speed` 本地插值） |
| `speed` | 播放倍速（1.0 为常速） |
| `offsetMs` | 服务器已应用的歌词偏移；**客户端不要再叠加** |
| `lyric` | `null`＝未匹配到任何歌词 |
| `lyric.live` | `true`＝播放器实时歌词（只有行文本，无时间轴），此时 `lines` 缺省，后续用 `line` 帧驱动 |
| `lines[].t / d` | 行起始毫秒 / 行时长毫秒（绝对时间） |
| `lines[].words` | 逐字时间轴，可缺省（＝行级歌词）；`words[].t/d` 同为绝对毫秒 |
| `hasArt` / `artVersion` | 有封面时用 `GET /cover?v={artVersion}` 取 JPEG |

### 4.2 `state` — 轻量进度锚点

播放期间约 **1 秒一次**；暂停/倍速变化/**seek**（位置偏离预测超过 ~700ms）时立即推送。

```json
{"v":1,"type":"state","ts":1760000001000,"data":{
  "active": true, "playing": true, "positionMs": 42210, "speed": 1.0,
  "durationMs": 223000, "track": true
}}
```

`track: true` 表示最后一个 `track` 帧的曲目身份仍然有效（`false` 期间按未定义处理）。

### 4.3 `line` — 实时歌词当前行（仅 `lyric.live` 模式）

```json
{"v":1,"type":"line","ts":1760000001000,"data":{
  "text": "正在唱的这一句", "nextText": "下一句", "translation": ""
}}
```

### 4.4 `pong` — 校时回显

`data.t0` 原样回显 `ping` 的信封 `ts`；信封 `ts` 是服务器发出时刻。

### 4.5 `error` — 服务器侧错误通知

`{"data":{"code":"bad_request","message":"…"}}`（code 稳定，message 仅用于展示）

## 5. 客户端 → 服务器消息

| type | 载荷 | 说明 |
|---|---|---|
| `hello` | `{protocol, client{name, version}}` | 能力握手，见 §3 |
| `ping` | 信封 `ts` = 本地毫秒 | 每 ~2s 一次校时；服务器回 `pong` |

**预留（v1 未实现，文档先行）**：
- `command` `{"action":"play-pause"|"next"|"previous"}` — 播放控制（服务器已有
  `MusicSessionReader.dispatchControl` 通路，客户端不得在 `welcome.capabilities`
  含 `command` 前发送）
- 服务器→客户端 `style` — 服务器侧统一下发样式（当前样式一律存显示端本地）

## 6. 时间模型（逐字平滑的关键）

服务器只发低频锚点，客户端本地插值出连续位置：

```
displayPos = positionMs + max(0, serverNow() - ts) * speed
serverNow() = localNow() - clockOffset
clockOffset ≈ medianOfLast5( (t0 + rtt/2) - t1 )      // 每 2s 一次 ping/pong
rtt = t2 - t0        // t0 客户端发出、t1 服务器回显、t2 客户端收到
```

- 新锚点到达时，若与本地预测相差在 **±250ms** 内则平滑吸附，否则视为 seek 直接跳变
- 行级/词级进度由客户端从 `lines[].t/d/words` 自行计算（通用端可任选渲染形态）

## 7. REST 降级端点（无 WebSocket 时可用）

| 端点 | 说明 |
|---|---|
| `GET /api/state` | `{"v":1,"track":<track.data>|null,"state":<state.data>}` |
| `GET /api/track` | `{"v":1,"track":<track.data>|null}` |
| `GET /cover?v=N` | 当前封面 JPEG（512px/q82）；版本不符或无封面返回 404 |

所有 REST 响应带 `Access-Control-Allow-Origin: *`；`/cover` 带 `Cache-Control: max-age=30`。
`GET /` 返回内置信息页（真正的显示页在仓库 `web-test-client/`）。
