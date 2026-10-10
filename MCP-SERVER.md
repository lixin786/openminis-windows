# 桌面端 MCP 服务端 · 第一批（只读）

> 状态：规格已定稿，待开工 13.1。待确认问题已全部关闭。来源：2026-10-10 与用户逐条确认。

## 范围

- **只做只读的 5 个工具。** 不提供写文件、编辑文件、shell、计划任务、子代理，也不转发 MCP 工具调用。
- **服务端默认关闭。** 要在「设置 › 系统 › 手机互联」里手动打开，关闭时不监听任何端口。

## 传输层（Streamable HTTP，协议版本 2025-06-18）

- 端点只有一个：`http://<局域网IP>:47821/mcp`，端口可在设置里改。
- **POST**：
  - `Accept` 必须同时包含 `application/json` 和 `text/event-stream`，否则返回 406。
  - 请求体是单条 JSON-RPC 消息；这个版本已取消批量请求，收到数组返回 400。
  - 带 `id` 的请求返回 `application/json`。通知和响应返回 202，没有响应体。
- **GET**：返回 405 并带 `Allow: POST, DELETE`，因为服务端不会主动推送。
- **DELETE**：结束带 `Mcp-Session-Id` 的那个会话，返回 204。
- **会话规则：**
  - `initialize` 不能带 session，成功后在响应头里下发 `Mcp-Session-Id`（128 位随机数，base64url 编码）。
  - 之后的每个请求都必须带这个头：不带返回 400，未知、已过期或已被淘汰返回 404。
  - **会话错误响应体必须让手机端自愈：** 手机端 `_is_session_error` 对 `HTTP <code>: <resp.text[:200]>` 做小写子串匹配 `session`，命中才会重新 initialize 并重试。所以：
    - 404 响应体固定为 `{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"session not found"}}`。
    - 缺少会话头的 400 响应体固定为 `{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"session required: missing Mcp-Session-Id"}}`。
    - `session` 一词必须出现在响应体的前 200 个字符内，所以这两种响应体保持短小、固定，不拼接任何可变内容。
    - 反过来，重新 initialize 解决不了的错误，响应体里不得出现 `session`，避免客户端反复重连。这包括 401、403、406、协议版本 400、批量请求 400 和请求体超限。
  - 同一会话第二次 `initialize`，返回 JSON-RPC 错误 -32600。
  - 在 `notifications/initialized` 之前调 `tools/*`，返回错误。
  - 闲置 30 分钟过期，最多同时 32 个会话；满了按最久未使用（LRU）淘汰，新会话照常建立，不拒绝。手机端不发 DELETE，daemon 重启会新建会话，这样反复重连不会撞上限。被淘汰的会话再来请求返回 404，客户端重新 initialize 即可。
- **版本协商：**
  - 只接受 `2025-06-18`。客户端在 initialize 里报了别的版本，回复 2025-06-18，由客户端决定是否断开。
  - 后续请求的 `MCP-Protocol-Version` 头**缺失时按 2025-06-18 接受**（上游手机端 `_post` 不发这个头）；只有「存在且不等于 2025-06-18」才返回 400。
- **支持的方法：** `initialize`、`notifications/initialized`、`ping`、`tools/list`、`tools/call`。
  - capabilities 只有 `tools: {listChanged:false}`。
  - 其他方法返回 -32601。

## 安全

- **鉴权：**
  - `Authorization: Bearer <令牌>`，令牌 32 字节随机，base64url 编码，用常量时间比较。
  - 鉴权失败返回 401，并带 `WWW-Authenticate: Bearer`。
  - 同一 IP 每分钟失败 5 次，锁定 60 秒。
- **令牌存放与轮换：** 放在应用数据目录。设置页「轮换令牌」一键生成新令牌并复制新连接串；轮换后旧令牌和它建立的所有会话立刻失效（后续请求 401）。
- **令牌不落日志：** 应用日志、审计日志、异常栈、错误响应体里都不出现令牌或 `Authorization` 头的值；连接串只在设置页显示和复制。测试会扫描日志输出，确认找不到令牌原文。
- **明文 HTTP（用户已接受，2026-10-10）：** 手机端 httpx 默认校验证书，自签证书连不上，局域网 IP 也拿不到受信任证书，所以这一版只走明文。开关旁固定显示警示条：「局域网明文传输，请勿在不可信网络（公共 Wi-Fi 等）开启。」
- **只绑局域网网卡：**
  - 枚举处于 up 状态的非回环网卡，只取私有 IPv4 地址（10/8、172.16/12、192.168/16），每个地址单独 bind。
  - 绝不 bind `0.0.0.0` 或公网地址，也不 bind 回环地址。
  - 网卡变化时，在设置页手动「刷新」重新绑定。
- **纵深防御：**
  - 再次校验请求来源 IP，必须是私有地址。
  - 带 `Origin` 头且不在允许列表里的请求返回 403，防 DNS rebinding。
- **限额：** 请求体最大 1 MB；单个 tool 调用 10 秒超时；同时最多 4 个调用。
- **审计：** 每次调用记录时间、来源 IP、工具、路径和结果字节数。设置页可以查看并清空。日志里不写令牌。
- **防火墙：** 首次监听时 Windows 会弹窗。文档会写明只勾选「专用网络」。

## 工具（全部只读，路径一律限定在工作区内）

路径统一用现有的 `PathGuard.resolve` 解析，roots 只包含工作区，不包含本地智能体的额外根目录：
- 拒绝 `..` 逃逸、指向工作区外的 symlink 或 junction、UNC 路径、`\\?\` 设备路径、NTFS 备用数据流（`a.txt:secret`）。
- 默认拒绝敏感文件：`.env*`、`*.pem`、`*.key`、`id_rsa*`、`.git/config`。这个列表在设置里可以关掉。

1. **`list_dir`** `{path?}`
   - 返回名称、类型、大小和修改时间，最多 1000 条，超出时标明截断。
2. **`read_file`** `{path, offset?, limit?}`
   - 只读 UTF-8 文本，返回带行号的内容。默认 2000 行、硬上限 256 KB，超出可用 offset 翻页。
   - 二进制文件拒绝读取。
3. **`search_files`** `{query, path?, glob?, case_sensitive?, max_results?}`
   - 字面量子串匹配，不支持正则（避免 ReDoS）。
   - 跳过二进制文件、大于 2 MB 的文件，以及 `.git`、`node_modules`、`build` 目录。
   - 默认 100 条、上限 500 条，每条包含路径、行号和这一行的内容。时间预算 5 秒，超时就返回已找到的结果。
4. **`memory_search`** `{keywords?, scope?, limit?}`
   - 复用 `MemorySearch`，scope 为 daily 或 all。
   - 记忆功能关闭时，这个工具不出现在 `tools/list` 里。
5. **`system_info`** `{}`
   - 返回应用版本、平台/OS、工作区路径、可用 shell（含 WSL 检测结果）。
   - 已配置的 MCP 服务器只给名称、状态和工具数。命令行、参数和环境变量可能含密钥，一律不给。

返回格式统一为 `content:[{type:"text"}]` 加 `structuredContent`，并为每个工具声明 `outputSchema`。工具出错时返回 `isError:true`，而不是 JSON-RPC 错误。

## 连接串（设置页一键复制）

```
minis-mcp-cli add --name desktop --url http://192.168.1.23:47821/mcp --header "Authorization: Bearer <令牌>"
```

已确认：上游 add 支持可重复的 --header，用户自定义的 header 原样保留（只强制覆盖 Accept/Content-Type），所以用静态令牌即可，不走 OAuth。

## 分批

- **13.1 服务端骨架**
  - 内容：基于 JDK 自带的 `com.sun.net.httpserver`，不新增依赖。实现传输层、会话、鉴权、局域网绑定、`ping`，`tools/list` 先返回空。设置页做开关、端口、令牌、复制连接串和绑定地址显示。
  - 测试：406、400、404、401、403、405；二次 initialize；未 initialized 先调用；协议版本头（缺失→放行、存在且不等→400、等于→放行）；绑定地址不含回环和公网（注入假网卡）；失败锁定；会话过期；轮换后旧令牌 401；日志扫描不含令牌；满 32 后 LRU 淘汰且被淘汰会话返回 404；405 带 Allow 头；404 和缺头 400 的响应体前 200 字符含 session，其他 4xx 响应体不含 session。
- **13.2 五个只读工具**
  - 内容：实现 5 个工具，接入 PathGuard、敏感文件拒绝列表、各项限额和审计日志。
  - 测试：路径穿越、symlink 或 junction 外逃、UNC、ADS、二进制拒绝、截断、搜索超时、记忆关闭时工具隐藏、system_info 不泄露 MCP 命令行。
- **13.3 收尾与真机联调**
  - 内容：当前会话列表、踢掉会话、审计日志界面、三语文案；你用手机 `add --url` 实测。
  - 每批都单路串行，全量测试全绿再交付。

## 待确认

1. ~~令牌怎么传~~：已确认用 `--header`。
2. ~~明文 HTTP~~：已接受，附三项条件（一键轮换、不写日志、UI 警示），见「安全」。
3. ~~404 时客户端是否会重连~~：会，前提是响应体里有 `session`，见「会话规则」。
