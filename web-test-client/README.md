# web-test-client — 微喵歌词同步 测试显示端

协议 v1 的参考网页客户端（纯 ES5 单文件，无构建工具），用于在电脑 / 平板 / 车机浏览器上
验证「微喵歌词同步」的云端歌词同步与逐字高亮。协议规范见仓库根 `docs/web-protocol.md`。

## 用法

手机 App 绑定门店后（首页「门店授权」卡可见门店号 barId），本页面有两种打开方式：

1. **带参数打开**：`index.html?barId=<门店号>`（适合做成车机书签）。
2. **双击直接打开** `index.html`，点左上角 ⚙ 填门店号 → 点「连接」；门店号会记住，
   下次自动连。

页面以 `control_box`（显示端）角色加入微喵云房间 `wss://socket.wemew.com/websocket/bluetooth_box/{barId}`，
App（`control_box_phone`）会在显示端进房后主动推送曲目快照。**无需知道手机 IP**，
手机与显示端只要能上网即可。

## 无手机调试（mock-server）

`mock-server.js` 实现了同一协议的模拟服务器（含切歌回放），用于离线调试页面：

```
node mock-server.js          # 默认端口 18903，WebSocket 在 /ws
```

mock 是局域网形态（页面已移除局域网直连，仅 mock 调试场景可用）——用 Node 直接测：

```
node -e "var w=new WebSocket('ws://localhost:18903/ws');w.onmessage=function(e){console.log(e.data.slice(0,120))}"
```

脚本会先推歌曲 A，800ms 后切换到歌曲 B，用于验证协议帧序列。

## 页面功能

- 逐字高亮（双层词级裁剪，词内按音节时长线性填充）
- ⚙ 设置：字号 / 基色 / 高亮色 / 字体族 / 翻译 / 下一句 / 门店号（存 localStorage）
- 键盘 ←/→ 微调歌词时间（±250ms，仅本机）
- 右上角连接点：青=已连云房间 / 灰=重连中
- 每 5s ping/pong 校时（最近 5 次取中位数），本地插值保证平滑逐字
