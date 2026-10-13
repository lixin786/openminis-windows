# P2-1 内置浏览器：交付说明（2026-10-13）

依据：用户拍板的 4 项决定（2026-10-13）+ 真机探针 `P2-1-EDGE-PROBE-WINDOWS.md`（main 8b5eab4）。
没有新增依赖：WebSocket 用 OkHttp 4.12 自带的，eTLD+1 用 OkHttp 自带的公共后缀表（`HttpUrl.topPrivateDomain()`）。

## 1. 结构

| 文件 | 内容 |
|---|---|
| `browser/BrowserPolicy.kt` | **纯函数**：顶级 URL 校验、IP 分段判定、eTLD+1 归并、白名单规范化、授权判断、截断、每轮导航计数、单页字节计数、启动参数、`DevToolsActivePort` 解析、代理请求行解析 |
| `browser/FilterProxy.kt` | 应用内过滤代理（127.0.0.1:随机端口）：**自己解析 DNS → 逐个判定解析出的 IP → 只连接判定过的那个 IP**；接入应用现有的上游代理设置（直连 / HTTP CONNECT / SOCKS，目标一律是已解析的 IP）；按页计字节，超过 20 MB 切断全部连接 |
| `browser/CdpClient.kt` | CDP 客户端：一个 WebSocket 连 browser 端点，标签页用 flat session；响应**按 id 匹配**，事件单独分发 |
| `browser/HeadlessBrowser.kt` | 启动 / 连接 / 标签页 / 事件；`Fetch` 拦截主框架文档请求做顶级导航把关；弹窗直接关闭；退出 = 杀进程树 + 按 profile 路径扫残留进程 + 删 profile |
| `browser/BrowserService.kt` | 生命周期（首次调用时启动、空闲 5 分钟杀进程树、应用退出清理）、每对话授权、7 个固定动作 |
| `browser/BrowserTool.kt` | `browser` 工具（只在主对话里）、审批、系统提示词片段、不可信内容标记 |
| `ui/BrowserSettings.kt` | 设置 › 工具 › 浏览器：开关、一句话风险说明、浏览器路径、白名单增删 |

## 2. 四项决定怎么落地

1. **默认开**：`AppSettings.browserEnabled = true`。开着时设置页显示一句风险说明（`browser.risk`）。
   关掉（或整个「代理工具」关掉）时：工具不进工具列表，`BrowserToolSpec.PROMPT` 不进系统提示词，正在运行的浏览器被杀掉；万一模型还是调用，返回 denied。
2. **授权 A**：按 eTLD+1，每个对话问一次，本对话内记住；审批框第二个按钮「始终允许此网站」把该域写进 `browserAllowlist`。白名单命中不再询问。
   - 「本对话内记住」存在内存里：**应用重启后同一对话会再问一次**（没写进会话文件）。如果你要的是跨重启记住，告诉我改。
   - 只有模型的 `open` 会弹审批。重定向、点链接、页面脚本跳转到**未授权**的域：直接拒绝（`Fetch.failRequest`），把原因和「请用 open 打开该网址以询问用户」告诉模型，而不是在导航过程中弹框（避免审批等待占用 30 s / 60 s 的计时）。
3. **不提供 evaluate**：模型只能用 open / text / links / select_text / click / type / screenshot。
   说明：取正文、取链接、定位元素这些动作**内部**仍用 `Runtime.evaluate` 跑 `Scripts` 里的固定脚本，选择器以 JSON 字符串字面量传入，模型写不进任何代码（有单测）。点击用真实的 `Input.dispatchMouseEvent`，输入用 `Input.insertText`。
4. **限值**：`BrowserLimits` 的默认值即用户定的数（有单测锁死）。导航 30 s、单次调用 60 s、文本 100k 字符（截断并注明）、单页 20 MB（代理计数）、每轮 20 次导航、3 个标签页、`Browser.setDownloadBehavior deny`、空闲 5 分钟杀进程树。
   - 「每轮」我理解为**用户发一条消息触发的一整轮**（不是模型的每一个工具回合）。重定向链按 `networkId` 只算一次。
   - 审批框等待时间不计入 60 s。

## 3. 七条硬约束

| # | 约束 | 实现 |
|---|---|---|
| 1 | `--disable-component-extensions-with-background-pages` | `BrowserPolicy.launchArgs`，单测断言 |
| 2 | 只用 `--remote-debugging-port=0` + 读 `DevToolsActivePort` | 同上；单测断言没有其他端口参数；解析函数单测（含半写入的文件） |
| 3 | 空闲退出 = 杀进程树 | `HeadlessBrowser.kill()`：先拍 `ProcessHandle.descendants()` 快照，再全部 `destroyForcibly`；代码里没有任何 `Target.closeTarget` 用于回收（只用来关弹窗） |
| 4 | 按 id 匹配响应、丢弃 / 分流事件 | `CdpRouter`，单测：事件先于响应、响应乱序 |
| 5 | 安全判定在代理里，代理自己解析 DNS 按 IP 判 | `FilterProxy`；单测：解析到 127.0.0.1 的域名被拒且确实是代理自己解析的、公网 + 内网混合解析结果整体拒绝、IP 字面量不经解析直接判定。另加 `--proxy-bypass-list=<-loopback>`，否则 Chromium 对 localhost 默认绕过代理 |
| 6 | 内存口径 | 文档与设置页都不写内存数字；对外说明请用你测的私有提交 181 MB |
| 7 | 退出清 profile、清 crashpad-handler | `ProcessSweep.killByProfile`（Windows：PowerShell `Get-CimInstance Win32_Process` 按命令行含 profile 路径杀进程，路径经环境变量传入避免引号问题）+ `deleteTree`（重试 10 次） |

被拦截的地址段：0.0.0.0/8、127/8、10/8、172.16/12、192.168/16、100.64/10、169.254/16（含 169.254.169.254）、224/4 组播、240/4（含广播）、::、::1、fe80::/10、fec0::/10、fc00::/7、ff00::/8，以及藏在 IPv6 里的 IPv4（::ffff:x、::x、64:ff9b::/96、2002::/16）。
URL 层还会按 Chromium（WHATWG）的规则解析 `http://2130706433/`、`http://0x7f.1/`、`http://0177.0.0.1/`、`http://127.1/` 这类写法。

## 4. 我这边实际跑过的

- 单测：`BrowserPolicyTest` 19 个、`BrowserPlumbingTest` 14 个。全量结果见提交说明（zh-CN 与 en 各跑一遍）。
- **端到端（Linux arm64 + Chrome for Testing 153 headless shell，不是 Edge，不是 Windows）**：`BrowserLiveTest`，默认跳过，设置 `OPENMINIS_LIVE_BROWSER=1` 和 `OPENMINIS_BROWSER=<浏览器路径>` 才跑。结果：
  - 打开 example.com：1.6 s（含冷启动约 1.3 s），正文、链接、选择器文本正常；
  - 启动后 target 只有 1 个 `page`；
  - 点链接跳 iana.org（未授权）被 Fetch 拦下并告诉模型——这证明「主框架 frameId = targetId」的判断在 153 上成立；
  - `127.0.0.1.nip.io`（公网域名解析到回环）：URL 层放行、代理自己解析后拒绝 ✔；
  - `http://127.0.0.1/` 在 URL 层直接拒绝，不弹审批 ✔；
  - 第 4 个标签页被拒 ✔；截图写出 PNG ✔；
  - 杀进程后：残留进程 0、profile 目录已删除（启动时进程树 9 个）✔。
  - 修过的一个真问题：Chromium 刚启动时初始 about:blank target 还拿不到 / 会被替换，`attachToTarget` 报 “No target with given id”。现在重试 1 s，拿不到再自己建一个。

## 5. 需要你在真机上手点 / 看截图的（拿不准的都在这）

**以下在 Edge 155 / Windows 上都没验证过，单测和 Linux 上的 Chromium 不能代替。**

1. **冷启动与 target 数**：第一次让模型 `open https://example.com/`，看日志 `Browser: started Edg/155… in N ms (… targets: page)`，targets 应只有 `page`。
2. **Fetch 拦截在 Edge 上是否成立**：打开 example.com 后让模型点「Learn more」，应得到「iana.org is not approved …」而不是直接跳过去。若在 Edge 上直接跳过去了，说明主框架判断失效，**这是安全问题**，请告诉我。
3. **代理拦截**：让模型打开 `http://127.0.0.1.nip.io/`（审批允许后）应被代理拒绝；`http://192.168.x.x/`、`http://169.254.169.254/` 应在不弹框的情况下直接拒绝。
4. **你的上游代理**：这条我完全没有测过。若 设置 › 网络 用了系统代理 / 手动 HTTP 代理（含 407 认证）/ SOCKS，请各开一个公网页面。注意：我们发给上游代理的是 **IP**（`CONNECT 93.184.215.14:443`），不是域名，以防上游再解析一次；如果你的代理（例如 Clash fake-ip 模式或要求域名的公司代理）不接受 IP，这里会失败。本地 DNS 解析不了、只能靠代理解析的域名也会失败——这是“代理自己解析 DNS”这条硬约束的直接代价。
5. **空闲 5 分钟退出**：用一次后放 5 分钟不动（检查间隔 15 s，所以是 5:00–5:15），任务管理器里不应再有带 `browser-profile` 的 msedge.exe / crashpad-handler，`%APPDATA%\OpenMinis\browser-profile` 应已删除。这个计时没有单测（没注入时钟），只在代码层面。
6. **退出应用时**：同上，进程与 profile 目录都应消失。PowerShell 扫残留那一步在 Windows 上没跑过。
7. **审批框截图**（三种语言）：标题「允许代理打开 example.com？」、风险提示一行、主按钮「本对话允许」、第二按钮「始终允许此网站」；点「始终允许」后 设置 › 工具 › 浏览器 的白名单里应出现该域。
8. **设置页截图**：浏览器小节（开关、风险说明、检测到的 msedge.exe 路径、白名单增删）；关掉开关后，下一轮模型拿不到 `browser` 工具。
9. **下载拒绝 / 弹窗关闭 / 权限提示**：只在代码里做了（`setDownloadBehavior deny`、新建且非我们创建的 page target 立即关闭、`--deny-permission-prompts`），没有端到端验证。
10. **内存**：没测。请沿用你的口径（私有提交约 181 MB）。

## 6. 我自己的取舍（不是你定的，可以改）

- 浏览器工具**只在主对话**里：子代理和定时任务（含后台执行）都没有它，因为它们的审批路径不同（后台遇到审批会直接取消整轮）。
- 截图存进工作区 `browser-screenshots/`，结果里只给路径和大小，**图片本身不发给模型**（现有工具结果只支持文本）。
- 被拒的导航之后，该标签页停在 `chrome-error://` 错误页，需要模型再 `open`。
- Edge 自己的后台请求（如网络时间同步）也经过代理，公网的照常放行，会计入当前页的 20 MB。
- 页面内容一律包在 `<untrusted_web_content>` 里，系统提示词写明页面里的指令不算用户指令。
- 日志只记 scheme + host + path（不记查询串，避免带出令牌）。
