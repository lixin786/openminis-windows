# P2-1 内置浏览器：选型报告（不含实现）

日期：2026-10-12。需求：模型能加载页面、跑 JS、取 DOM；不需要可见窗口。约束：现有交付物为 uber-jar（51.2 MB）+ MSI（69 MB），不能让安装包大幅膨胀。

## 实测数字

以下数字均为 2026-10-12 实际下载后测得：用 `curl` 取 Content-Length，或下载后解压、重新打包测量。

| 方案 | 组件 | 下载/压缩体积 | 解压后 | 备注 |
|---|---|---|---|---|
| (a) JCEF | `me.friwi:jcefmaven:152.0.6` → `jcef-api`（Maven Central） | 0.2 MB | — | Java 绑定 |
| | `jcef-natives-windows-amd64`（CEF 152.0.6 / Chromium 152.0.7977.83） | **171.2 MB**（jar 内是一个 tar.gz） | **389.3 MB**，240 个文件；其中 `libcef.dll` 284.9 MB | 152 版的 natives **不在 Maven Central 上**，jcefmaven 默认在首次运行时从 GitHub Releases 下载；Central 上最新只到 132 版（137.6 MB） |
| | 按 uber-jar 的 deflate 级别重新打包 natives 目录 | **171.3 MB** | — | 几乎压不动 |
| (b) Playwright-java 1.63.0 | `playwright` | 0.7 MB | — | |
| | `driver`（JS 包，`driver/package`） | 3.2 MB jar | 13.5 MB | |
| | `driver-bundle`（5 个平台的 Node） | **203.8 MB** | Windows 部分 `node.exe` 93.6 MB；只保留 win32_x64 时重新打包为 **35.0 MB** | 默认依赖会带上全部平台 |
| | Chromium（Chrome for Testing 153.0.8010.12，browsers.json revision 1243） | 完整版 `chrome-win64.zip` **205.1 MB**；`chrome-headless-shell-win64.zip` **120.2 MB** | — | 默认运行时从 `cdn.playwright.dev` 下载到 `%LOCALAPPDATA%\ms-playwright` |
| (c) 系统 Chrome，经 CDP | WebSocket 客户端 | **0 MB 新增**：OkHttp 4.12 自带 WebSocket，已在依赖里 | — | 只需写一个 CDP 客户端，估计几百行 |
| (d) 推荐：自行启动系统 Edge/Chrome，headless + 独立 profile + CDP | 同上 | **0 MB** | — | 见下文 |
| (d2) 无浏览器的 HTTP 取页 + HTML 解析 | 可选 jsoup | 约 0.45 MB | — | 不能跑 JS |

**未实测**（本机不是 Windows，需在真机上确认）：Edge headless 冷启动耗时、内存占用，以及 JCEF OSR 在我们 Skiko/D3D 窗口里的表现。真机上 30 秒就能验证 Edge 那条路：
`"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" --headless=new --user-data-dir=%TEMP%\om-probe --dump-dom https://example.com`

## 逐项评估

### (a) JCEF
- **artifact**：`me.friwi:jcefmaven`，natives 为 `jcef-natives-windows-amd64`（GitHub Releases；旧版本在 Central）。也可以改用 JetBrains Runtime 的 JCEF 版本，但那等于把我们打包的 JDK 整个换掉，改动更大，本次未测体积。
- **离屏**：CEF 本身支持 windowless（OSR）。但 JCEF 的 Java 侧 OSR 渲染走 JOGL 的 GLCanvas，仍然需要挂一个 AWT 组件；真正的"无窗口、只取 DOM"要靠一个隐藏的 OSR 浏览器加 `executeJavaScript` 回调，取 DOM 只能借 JS → `CefMessageRouter` 回传。可行，但 API 很别扭，没有 CDP 那样的 `DOM.*` / `Runtime.evaluate` 返回值。
- **能否进 uber-jar**：DLL 和 `jcef_helper.exe` 必须落盘才能加载，没法从 jar 内直接用。打进 jar 只是"带着走"，首次运行仍要解压出 389 MB。uber-jar 会从 51 MB 涨到约 **222 MB**，MSI 估计从 69 MB 涨到 **240 MB 上下**。
- **打包改动**：中等。MSI 需要把 natives 目录作为 app resources 外置（`appResourcesRootDir`）；uber-jar 需要首次运行解压逻辑；外加 Chromium 子进程的生命周期、崩溃隔离，以及跟 Skiko/D3D 同进程共存的风险（两套 GPU 栈在同一进程）。
- **结论**：体积和同进程风险都不划算，**不推荐**。

### (b) Playwright-java
- **随包分发**：driver + Windows Node 约 35 MB（需要自己裁掉其他平台，默认的 driver-bundle 有 204 MB），Chromium headless shell 再加 120 MB。安装包会涨到 **约 220 MB**。
- **运行时下载**：jar 只多约 4 MB，但首次使用要下载约 35 MB 的 Node（或打进包里）外加 120 MB 的 headless shell，从 `cdn.playwright.dev` 下载，国内网络常常很慢甚至失败。还要让下载走我们的代理设置（Playwright 读 `HTTPS_PROXY`）。体验是：首次调用卡几十秒到几分钟，需要进度 UI 和失败重试。
- **其他代价**：多一个 Node 进程（driver），多一层 IPC，版本跟 Chromium revision 绑死。API 很好用。
- **结论**：功能最全，但对"不炸包"的约束来说最重，**不推荐作为默认**。

### (c) 连接系统里已带 `--remote-debugging-port` 运行的 Chrome
- 体积为零，但**不适合作为发布版依赖**：
  1. 普通用户没有这样一个 Chrome；
  2. 那个实例带着它主人的登录态和 cookie，模型拿到 CDP 就等于能用用户身份操作任意站点，这是凭证外泄的最大口子；
  3. Chrome 136 起，`--remote-debugging-port` 对默认 profile 不再生效，必须配合自定义 `--user-data-dir`，用户自己的工具链本来就是特例。
- 可以作为**高级选项**（"连接到现有 CDP 端点"，默认关、填 URL、首次使用强提示），给有自己工具链的用户用。不建议 v1 做。

### (d) 推荐：由应用自己启动系统 Edge（Chrome 兜底），headless + 应用专用 profile + CDP
- Win10 / Win11 都预装 Chromium 内核的 Edge（`msedge.exe`）；找不到时再找 Chrome。都没有（例如 Windows 10 LTSC 2019 等精简版）时，工具显示不可用并说明原因。v2 可以再加"按需下载 Chrome headless shell（120 MB）"作为可选项。
- 启动：`msedge.exe --headless=new --user-data-dir=%APPDATA%\OpenMinis\browser-profile --remote-debugging-port=0 --no-first-run --proxy-server=127.0.0.1:<应用内过滤代理> --force-webrtc-ip-handling-policy=disable_non_proxied_udp ...`，再从 profile 下的 `DevToolsActivePort` 读出端口，只绑 127.0.0.1。
- 调用：OkHttp WebSocket + 精简 CDP 客户端（`Target` / `Page.navigate` / `Runtime.evaluate` / `DOM.getOuterHTML` / `Input.*` / `Browser.setDownloadBehavior`）。
- **包体积 +0**，进程隔离（浏览器崩溃不会拖垮应用，也不会跟 Skiko/D3D 抢同一个进程），Chromium 由 Windows Update 自动更新，安全补丁不用我们跟。
- 代价：Edge 版本不受控，CDP 只用稳定子集；`--remote-debugging-port` 在浏览器存活期间对本机其他进程可见。缓解办法：随机端口、空闲 N 分钟自动退出、profile 专用。更彻底的 `--remote-debugging-pipe` 需要 JNA `CreateProcess` 继承句柄，可以作为 v2 加固项。
- 另配 (d2)：一个**不跑 JS 的 `web_fetch`**（HTTP 取页 → 正文/链接文本），覆盖大部分"读个网页"的需求，既快又便宜；需要 JS 时才拉起浏览器。

## 安全方案（需要拍板）

浏览器等于"模型可以把数据发到任意 URL"。建议的分层如下：

1. **总开关「启用浏览器工具」默认关**（设置 › 工具），打开时用一句话说明风险。关闭时既不出现在工具列表里，也不进系统提示词。**建议：要。**
2. **网络硬约束（不可在 UI 里放开）**
   - 只允许 http / https；拒绝 `file:`、`chrome:` / `edge:`、`devtools:`、`javascript:`、`data:`（顶层导航）。
   - 拦截回环、私网、链路本地等地址：回环 127/8、::1；私网 10/8、172.16/12、192.168/16；CGNAT 100.64/10；链路本地 169.254/16（含云元数据地址）、fe80::/10；fc00::/7；0.0.0.0；组播。
   - **关键做法**：浏览器的所有流量（含子资源、XHR、重定向、WebSocket）都走**应用内的过滤代理**。代理自己做 DNS 解析，按解析出的 IP 判定后再自己连接，这样能防 DNS rebinding（只在 URL 层检查会有检查时与使用时不一致的漏洞）。代理同时套用应用已有的上游代理设置，并负责计数和限流。WebRTC 的 UDP 用启动参数禁掉，避免绕过代理。
3. **审批粒度**，三选一，或组合：
   - A. 按**可注册域名**（eTLD+1）审批，每个会话只问一次，会话内记住；审批框提供「总是允许」，写入设置页白名单。
   - B. 只用设置页白名单，名单外一律拒绝：最严，但模型基本没法"随便搜点东西"。
   - C. 首次启用时一次性允许所有公网地址：最松，只靠第 2 条兜底。
   - **建议 A**。审批只管**顶层导航**的域名；页面自带的子资源在第 2 条约束内放行，否则几乎所有网站都打不开。
4. **模型自写 JS（`evaluate`）单独一档**：页面自己的脚本我们管不了，但模型写的 JS 能对任意地址发 `fetch`，正好绕过第 3 条。建议 v1 只提供固定动作：打开、取正文/链接/选择器文本、点击、输入、截图。`evaluate` **每次调用都审批**，或者干脆不提供。**这条请拍板。**
5. **上限**（默认值，可以改）
   - 单次导航超时 30 秒，单次工具调用总时长 60 秒；
   - 返回给模型的文本不超过 100k 字符（超出部分截断并注明）；
   - 单页传输上限 20 MB（代理计数）；
   - 每轮最多 20 次导航；同时最多 3 个标签页；
   - 下载一律禁止（`Browser.setDownloadBehavior: deny`），不弹窗、不开通知、不给地理位置和摄像头等权限；
   - 浏览器空闲 5 分钟后自动退出。
6. **身份隔离**：专用 profile，不碰用户自己的浏览器；退出应用时清理 cookie 和存储（或者每个会话一个临时 profile，更干净但更慢）。v1 不支持连接用户自己的浏览器。
7. **防注入**：页面内容进入上下文时打上"不可信网页内容"的边界标记，系统提示词写明页面里的指令不算用户指令。
8. **审计**：导航和审批写入日志，复用 13.2 的审计格式。

## 推荐结论

- **实现路线**：(d) 系统 Edge/Chrome + 专用 profile + 应用内过滤代理 + 自写 CDP 客户端，外加 (d2) 不跑 JS 的 `web_fetch`。包体积几乎不变，进程隔离，补丁随系统更新。
- **不推荐**：JCEF（+171 MB、同进程两套 GPU 栈）、Playwright（随包约 +155 MB，或者首次运行下载 155 MB）、依赖用户自己的调试 Chrome（凭证风险）。
- **待拍板**：第 1 条（建议默认关）、第 3 条（建议 A）、第 4 条（`evaluate` 每次审批还是不提供）、第 5 条的默认数值。
