# P2-1 浏览器方案：Windows 真机探针实测（2026-10-13）

方案 (d)（系统 Edge/Chrome 无头 + 应用内过滤代理 + 自写 CDP 客户端）在**真实 Windows 机器上实测通过**。
本文所有数字均为本机实测，不是估算。

机器：Windows 11 中文版，`msedge.exe` 版本 **155.0.4283.45**
（`C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`），
`chrome.exe` 版本 154.0.8037.97 亦存在，可作回退。

## 1. 启动与冷启动耗时

启动参数（已按实测结果收敛）：

```
msedge.exe --headless=new
           --user-data-dir=%APPDATA%\OpenMinis\browser-profile
           --remote-debugging-port=0
           --no-first-run --no-default-browser-check
           --disable-gpu
           --disable-component-extensions-with-background-pages
           --disable-background-mode --disable-default-apps
           --no-pings --disable-breakpad
           --proxy-server=http://127.0.0.1:<应用内过滤代理端口>
           about:blank
```

- **冷启动到 `DevToolsActivePort` 文件出现：263 ms / 299 ms**（两次测量）。
- **到 CDP WebSocket 连上并可用：437 ms**。
- 端口用 `--remote-debugging-port=0` 让系统分配，从 profile 目录下的 `DevToolsActivePort`
  文件第一行读取（第二行是 browser 级 ws 路径）。这是唯一可靠的取端口方式，不要用固定端口。

## 2. 必须带 `--disable-component-extensions-with-background-pages`

不带该参数时，Edge 会自动加载 5 个内置扩展的后台页：

```
TARGET_TYPES=background_page,background_page,background_page,background_page,background_page,page
N_TARGETS=6
```

带上之后：

```
N_TARGETS=1  TYPES=page
```

即目标列表干净到只剩我们要的那一个 page target。**这是实现时必须带的参数**，
否则会平白多出 5 个不可控的后台页。

## 3. CDP 子集全部实测可用

用 .NET `ClientWebSocket`（等价于实现里的 OkHttp WebSocket）直连 page target 的
`webSocketDebuggerUrl`，`WS_STATE=Open`。以下调用均返回正常结果：

| 调用 | 实测返回 |
|---|---|
| `Browser.getVersion` | `protocolVersion: 1.3`, `product: Edg/155.0.4283.45` |
| `Page.enable` | ok |
| `Page.navigate {url}` | `frameId` + `loaderId` + `isDownload:false` |
| `Runtime.evaluate {expression, returnByValue:true}` | 真实页面返回 `"Example Domain"`；`document.title + ' | ' + location.href` 返回 `Example Domain \| https://example.com/ \| len=1974` |
| `DOM.getDocument` → `DOM.getOuterHTML {nodeId}` | nodeId=1，HTML 3789 字符，`<!DOCTYPE html><html lang="en">...` |
| `Browser.setDownloadBehavior {behavior:"deny"}` | `{}` |
| `Input.dispatchMouseEvent {type,x,y}` | `{}` |
| `Target.closeTarget {targetId}` | `{"success":true}` |

**结论：`Runtime.evaluate`（带 `returnByValue`）真的能拿到页面里的值**，
这是读页面内容的主路径，已证实可行。

## 4. `--proxy-server` 被真实遵守（安全设计的基石）

搭一个只 `Accept` 不回复的裸 TCP 监听器在 `127.0.0.1:8899`，带
`--proxy-server=http://127.0.0.1:8899` 启动 Edge 后，监听器**实际收到**：

```
GET http://edge.microsoft.com/browsernetworktime/time/1/current?cup2key=... HTTP/1.1
```

这证明两件事：

1. 浏览器的出站流量**确实全部**走应用内代理，包括 Edge 自己发起的后台请求
   （连它自己的网络时间同步都走代理）。因此「在 CONNECT/GET 层看到目标 host 并按解析后的 IP 判定」
   这一套是可以落地的，也就是 **DNS rebinding 防御成立的位置就在这个代理里**。
2. 代理不回复时导航确实被挂住（`Page.navigate` 响应超时、`Runtime.evaluate` 取不到值），
   说明「拒绝即可阻断」有效——**门是真的能关上的**。

实现要点：代理必须自己解析 DNS、自己判定解析后的 IP，不能只看 URL 里的主机名
（只看 URL 层判定存在 TOCTOU 漏洞，DNS 可以让域名先解析到公网再解析到内网）。

## 5. 内存：看私有提交，不要看工作集

真实页面（example.com）加载完成、静置 6 秒后，按进程拆解：

| 类型 | 工作集 MB | 私有提交 MB |
|---|---|---|
| browser | 161.2 | 60.1 |
| renderer | 60.7 | 26.8 |
| renderer | 59.7 | 24.8 |
| utility | 38.7 | 14.1 |
| gpu-process | 36.1 | 14.6 |
| renderer | 30.9 | 16.2 |
| utility ×3 | 53.3 | 22.0 |
| crashpad-handler | 8.7 | 2.4 |
| **合计（n=10）** | **449.4 MB** | **181.1 MB** |

- **工作集合计 449 MB 是误导性数字**：Edge 多进程之间共享代码页/快照被逐进程重复计入。
  **私有提交 181 MB 才是这个方案真实的额外内存代价**，对外说明请用这个数。
- 进程数 10~11 个（1 browser + 3~4 renderer + gpu + 3 utility + crashpad）。
- **关闭标签页不会回收内存**：`Target.closeTarget` 之后静置 6 秒，内存反而涨了
  （sumWS 449 → 549 MB，sumPRIVATE 181 → 265 MB，某个 renderer 从 26.8 涨到 77.2 MB）。
  **回收内存的唯一手段是杀掉整个进程树**。这直接决定了「空闲 5 分钟自动退出」
  必须是杀进程树，而不是关标签。

## 6. 对实现的硬性影响

1. 必须带 `--disable-component-extensions-with-background-pages`。
2. 端口必须用 `--remote-debugging-port=0` + 读 `DevToolsActivePort`，禁止固定端口。
3. 空闲退出 = 杀进程树（按启动 pid 连带子树），不是 `Target.closeTarget`。
4. 走 CDP 之前要能容忍事件消息混在响应里：`Page.navigate` 会先推一堆
   `Page.frameNavigated` 等事件，接收端必须按 `id` 匹配响应并丢弃事件，
   不能假设「发一条收一条」。
5. 崩溃残留：`crashpad-handler` 进程仍会起（`--disable-breakpad` 未完全阻止），
   退出时要一并清掉。
6. 应用退出时清 profile 目录（实测 `about:blank` 空跑就产生 262 个文件 / 6.75 MB，
   加载一个页面后 13.99 MB）。

## 7. 未验证项（留给实现阶段）

- Edge 版本漂移：本机 155，CDP 只用稳定子集（上表 8 个调用）可以规避，但需要
  一个「version 兜底」：连不上 / 调用返回 `method not found` 时给出明确错误，而不是静默失败。
- 代理 + CDP 同时工作时 `Page.navigate` 的响应时序（本次探针里代理故意不回复，
  导致 navigate 响应超时；正常回复时应有响应）。
- 下载拒绝、弹窗拦截、WebRTC UDP 关闭等策略只验证了 API 可调用，没有做端到端行为验证。
