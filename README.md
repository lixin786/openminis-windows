# OpenMinis — Windows 桌面版移植

把开源项目 [OpenMinis](https://github.com/OpenMinis/OpenMinis)（原版为 iOS Swift + Android Kotlin 的 AI Agent，GPLv3）移植成 **Windows 桌面版**，基于 Compose Multiplatform for Desktop。

- **上游基线**：`OpenMinis/OpenMinis` @ commit `b4c0661`（v1.14）
- **本仓库内容**：移植产物源码，入口在 **`src/desktop/`**
- **许可证**：GPLv3（与上游一致，见 `src/desktop/LICENSE`）
- **移植说明与功能对照表**：**`src/desktop/PORTING.md`**（必读，里面有「已移植 / 已替换 / 未移植 / 桌面端不适用」的完整清单与路线图）

## 当前状态

已完成并交付过两批，本仓库是 **hotfix1** 之后的源码状态（421 个测试通过）：

| 批次 | 内容 |
|---|---|
| batch1（P0） | 上下文压缩、图片/附件输入、模型回退链、中英文分词记忆检索 |
| hotfix1 | 修复 UTF-8 解码乱码（中文 Windows 上按 GBK 解流式响应）、窗口尺寸超出屏幕、新增网络代理设置（跟随系统 / 直连 / 手动） |
| hotfix2 | HTTP/2 流重置：设置 → Network → HTTP protocol（Auto / HTTP/1.1 only）；Auto 下遇 `stream was reset` 按主机自动降级 HTTP/1.1 并立即重试；重置来源诊断（本地 cancel / 对端）；修连接超时输入框被钳到 3 秒；补回 gradle-wrapper.jar |
| batch3 | P1：技能导入界面（GitHub 链接/zip/文件夹/粘贴，开关/删除）；计划任务（定时/延时/间隔/接力，新对话或续写，运行记录，agent 工具 scheduled_task；**只在应用运行时触发，应用不运行时不会执行**）；MCP 拒绝启动后对话内提示 |
| batch2 | P1：MCP stdio 客户端（mcp_servers.json，Claude Desktop 配置可直接导入，启动前确认，工具以 mcp__服务器__工具 暴露）；子智能体（subagent_task，与上游同 schema，并发/深度上限，子任务命令仍需确认）；修复等待响应头时 Stop 卡住 |
| batch3 补充 | 计划任务窗口与新建/编辑表单顶部常驻提示：**应用不运行时不会执行**（到点时应用未打开，这一次就跳过；延时/间隔任务在下次启动时补跑一次）。设置窗口、计划任务窗口按当前屏幕可用区域（减去边距）限制初始尺寸并居中，可调整大小，内容可滚动且有滚动条——1366×768 @125–150% 的小屏也能滚到「Skills → 导入」按钮 |
| batch4 安全加固 | 见下方「安全加固」完成情况：denylist 硬拦截（POSIX + Windows，嵌套 sh -c / eval / $( ) / cmd /c / powershell -Command / -EncodedCommand 递归扫描，`${IFS}` 展开，变量目标 fail-closed）、元字符守卫、只读白名单免确认（设置项，默认开）、路径硬约束（realpath / symlink / junction / UNC / ADS / 8.3）、fail-closed、攻击回归测试 41 条、Shell 侧无任何持久放行、PROVENANCE.md；全量 520 个测试通过 |
| hotfix3 | 经代理在响应头之后被掐断流时提示「本地代理可能掐断流式连接，建议直连 / 加入绕过列表」；失败诊断（来源 LOCAL/PEER、协议、路由、耗时）写入可复制的错误文本 |

已在真 Windows 11 x64 + Temurin JDK 17 上验证通过的项目：

- 启动与界面渲染正常；数据目录 `%APPDATA%\OpenMinis` 自动创建
- 流式对话正常（SSE），顶栏 `ctx ≈x / y` 上下文计数正常
- 中文与 emoji 显示正确（乱码已修）
- 窗口限制在屏幕可用区域内并居中
- shell 工具直接在宿主机执行（PowerShell / cmd / WSL），工作区自动创建
- 回退链按 1s/2s/4s 退避重试；TLS 看门狗正常

## 已知阻塞（hotfix2 已处理，待真机复测）

OkHttp 走 **HTTP/2** 时，流式响应会在**精确 +3004ms** 报
`StreamResetException: stream was reset: CANCEL`：响应头能正常拿到（`status=200`、`text/event-stream`），但正文收不到一个字节。

已排除：

- **不是本地代理** —— 网络模式改为直连（`chain=DIRECT`、TLS 380ms 正常）后仍然在 3004ms 被重置
- **不是网关或模型** —— 同机同网关同请求，`curl` 走 HTTP/1.1 跑 10.1 秒、收到 58 个 SSE 事件、15KB 数据完全正常

建议方向：加「强制 HTTP/1.1」选项，或在遇到 `StreamResetException` 时自动降级到 HTTP/1.1 重试
（OkHttp：`protocols(listOf(Protocol.HTTP_1_1))`）。当前回退链对同一个 stream reset 会连试 3 次，
若确认是协议层问题，降级协议比重复重试更有效。

## 尚未在真机验证

shell 确认框的 Enter 允许 / Esc 拒绝、文件工具、记忆、重启持久化。

## 需求约束（继续开发时请遵守）

1. **不要沙箱**：用户明确要求直接运行在 Windows 上调用。`PORTING.md` 里"可选 Windows Sandbox / AppContainer / WSL-only 隔离"已删除，不要重新引入；但**「命令执行前确认」默认开启必须保留**，这是无沙箱下唯一的安全网。
2. **功能与原版对齐**：以 `PORTING.md` 的「Dropped or stubbed」和「Known limitations」为清单逐项补齐；手机端独有功能（通知/前台服务/无障碍/分享/deep links/小组件/语音）标注为「桌面端不适用」而非待办。
3. **交付形态**：源码 + 可运行 jar + 可安装 MSI / EXE。
4. 保持 GPLv3 与上游 attribution；不引入需要付费服务的强制依赖。

## 后续路线

修完上面的协议问题后继续 P1：MCP（stdio）客户端、子智能体、技能导入 UI（GitHub/zip 导入 + 每会话覆盖）、计划任务（用 Windows 任务计划程序）。

## 待补需求（2026-10-09 用户明确提出，优先级高于原 P2）

### 1. 汉化（最高优先）

现状：桌面端把上游的字符串资源层整个丢了，**UI 里是硬编码英文**，中文用户看到全英文界面。

上游有现成的料，**这是搬资源不是从零翻译**：

- `src/android/app/src/main/res/values-zh/strings.xml`（简体）、`values-zh-rTW/strings.xml`（繁体），共 18 个语言目录
- 本地化工具链：`scripts/add_locale.py`、`add_localization.py`、`register_locale.py`、`check_android_locale.py`、`check_locale_batch.py`

做法建议：在 desktop 层引入字符串资源（或轻量的 i18n 表），把 UI 里的硬编码英文抽出来，简体/繁体直接复用上游译文；回退语言用英文。顺带把语言做成设置项并跟随系统。

### 2. UI 升级（分批做）

现状：桌面 UI 是紧凑重写（`desktop/ui/*`：可调宽侧栏 + 对话面板、Material 3、Markdown、工具卡片、thinking 折叠、设置窗口），功能比手机端少很多。上游 `ui/chat/` 一个目录就有 161 个文件。

建议先挑高价值项做，不要一次追平：

- 会话管理（重命名、分组/置顶、搜索增强——现在是内存子串匹配，无 FTS）
- 工具卡片与会话内交互细节（展开、重跑、复制、引用消息）
- 每会话 MCP 面板（上游有 `ui/chat/SessionMcpsSheet.kt` 可参考）
- 字体/主题/密度等观感项

### 3. 与手机 minis 互联通讯

**不要自创协议**：上游手机端本来就自带 MCP daemon ——

- `src/android/app/src/main/assets/default_mount/usr/local/lib/minis-mcp-cli/`（`daemon.py`、`main.py`、`transport/stdio.py`、**`transport/http.py`**）
- iOS 侧对应 `src/ios/MinisTests/Standalone/MCPBridgeReachabilityVerifyTests.swift`
- 上游 app 里也有 `app/mcp/`（含 `oauth/McpPkce.kt`）与 `ui/chat/SessionMcpsSheet.kt`

即：**手机的 minis 本来就能作为 MCP 服务被连接**。桌面端 P1 要做 MCP 客户端，因此加一个 **HTTP transport** 就能连手机，方向是桌面当客户端、手机当服务端。

默认方案：**局域网直连**（同一 WiFi，桌面直连手机 minis 的 HTTP 端口，数据不出局域网；需要手机端开放 MCP 监听 + 一次性配对）。若日后需要跨网络，再考虑经中转服务器，并另加配对与认证。

参考可复用件：`backup/remote/LocalFolderDelivery.kt`（文件级交换的现成实现，可作为降级方案）。

## 安全加固（下一批，做完当前 P1 批次后优先于汉化）

现状核查（`grep` 全库确认）：桌面端目前的安全模型**只有**「每条 shell 命令执行前确认 + 工作区外写入确认」，即
**没有 denylist 硬拦截、没有白名单、没有元字符分析、越界路径只提示不硬拒**。也就是说模型被 prompt 注入后要求执行
`rm -rf /`，没有任何东西拦它，只能指望用户在看弹框时认出危险。

参考同类实现（一个 Electron 版 PC 移植，它做过攻击者视角审计并挖出 2 个严重漏洞）的五层门禁，需补齐：

### 1. denylist 硬拦截（必须）

独立纯函数模块（无 UI 依赖，可单测），命中即拒绝，并在工具结果里说明拒绝原因。至少覆盖：

- `rm` 递归且目标为 `/`、`/*`、`~`、`$HOME`、裸 `*`（**路径先 normalize**，`/tmp/../` 要折叠成 `/`）
- `mkfs*`；`dd` 且 `of=/dev/`（含 `of = /dev/` 空格变体）；`> /dev/sdX` 重定向写块设备；`shred`/`wipefs` 操作 `/dev/`
- fork 炸弹；`shutdown`/`poweroff`/`reboot`/`halt`
- `chmod -R` 指向 `/`；`mv` 目标为 `/`
- **必须递归扫描嵌套载荷**：`sh -c "…"`、`bash -c '…'`、`eval "…"` 里藏的真实命令要用同一套规则再扫一遍
- 预处理 `${IFS}` / `$IFS` 展开；`rm -rf /$x` 这类"目标危险且含变量展开"的写法 fail-closed 拒绝

### 2. 元字符守卫（必须）

命令里出现 `>` `<` `|` `;` `&`、反引号、`$(`、`)` 时，**一切"免确认"快路径立即失效，必须转用户确认**。
这类漏洞的真实形态：`echo 公钥 >> ~/.ssh/authorized_keys` 被当作只读的 `echo` 直接放行。

### 3. 白名单免确认（建议）

只读命令（`ls` `cat` `grep` `head` `tail` `wc` `date` `which` 等）**且不含上述元字符**才免确认。
目的是降低确认疲劳——用户被反复询问后会无脑点允许，这本身就是风险。

### 4. 路径硬约束（必须）

`file_read` / `file_write` / shell 的 cwd：realpath 解析后**越界硬拒绝**（现在只是弹框提示）；
symlink 不得逃逸（`~/link -> /tmp` 这类）；目标不存在时解析最近的已存在祖先再判断。

### 5. fail-closed（必须）

没有可用窗口、审批异常、弹框出错时**一律拒绝**，不允许出现"默认放行"。

### 6. 攻击回归测试（必须）

单独一组用例覆盖上述攻击路径，作为常驻断言。现有 458 个都是功能测试，**没有一条攻击用例**。

### 7. 解释器永不记住

`sh`/`bash`/`zsh`/`python`/`python3`/`node`/`perl`/`pwsh`/`powershell`/`cmd` 等解释器
**不得**写入任何持久化批准。MCP 的"永久允许"已绑定 `command/args/env/cwd` 的 SHA-256 指纹（保持这个设计），
但 Shell 侧不允许出现永久放行。

### 8. PROVENANCE.md（建议）

补一份正式来源声明（上游仓库、锁定 commit、GPLv3 义务），与 LICENSE 并列。

### 验收方式

安全回归测试全绿；并在真 Windows 上跑三条真实用例确认行为：① 越界写文件被拒；
② 注入式只读命令（`echo x >> ~/.ssh/authorized_keys`）被要求确认而不是直接放行；③ 危险命令（`rm -rf /` 变体）被硬拦截。

### 完成情况（batch4，2026-10-09）

代码在 `src/desktop/app/src/main/kotlin/com/openminis/desktop/security/`（`CommandGuard.kt`、`PathGuard.kt`、`Approvals.kt`），细节与偏差见 `src/desktop/PORTING.md`「Security hardening」。

| 层 | 状态 | 要点 |
|---|---|---|
| 1 denylist | ✅ | 命中即拒绝，不弹确认框，工具结果写明原因。覆盖上面所列 POSIX 规则，并补齐 Windows：`rd/rmdir /s`、`del /s`、`format C:`、`Remove-Item -Recurse`（及别名 rm/ri/del/rd/rmdir）指向盘符根 / `%USERPROFILE%` / `$env:USERPROFILE` / `~` / `C:\Windows`、`diskpart`、`cipher /w`、`bcdedit`、`Stop-Computer`/`Restart-Computer`、`shutdown /s /r`、`vssadmin delete shadows`、`wmic shadowcopy delete`、`reg delete HKLM`、`Format-Volume`/`Clear-Disk`。嵌套载荷递归扫描：`sh/bash -c`、`eval`、`$( )`、反引号、`cmd /c`、`powershell -Command`、`-EncodedCommand`（base64 UTF-16LE 解码后再扫，解不开直接拒绝）、`sudo/env/timeout/xargs/wsl` 前缀。MCP 启动命令也过同一 denylist |
| 2 元字符守卫 | ✅ | 含 `> < \| ; & \` $ ( )` 或换行：免确认路径全部失效，即使关闭「每条命令都确认」也照样弹框；调用解释器、含嵌套载荷的命令同样处理 |
| 3 白名单 | ✅ | 设置 › Tools & shell › Run read-only commands without asking（默认开）；ls/cat/grep/head/tail/wc/date/which/dir/type/where/findstr/Get-ChildItem/Get-Content/Select-String/echo 等，且无元字符、参数不越出工作区、不含 `-exec/-delete/-o/-i` 等写入参数；find/sed/awk/xargs/env/git 不在白名单 |
| 4 路径硬约束 | ✅ | file_read / list_dir：工作区 + 只读的 skills、attachments 目录；file_write / file_edit / shell cwd：仅工作区。越界一律硬拒绝（原「工作区外写入确认」设置已删除）。realpath、symlink/junction、目标不存在取最近祖先、`\\?\`、UNC、8.3 短名、`C:foo`、ADS `a.txt:s`、保留设备名均处理 |
| 5 fail-closed | ✅ | 无窗口 / 审批异常 / 弹框出错 / 10 分钟无应答 → 拒绝；子智能体、计划任务、MCP 同一路径 |
| 6 攻击回归测试 | ✅ | `security/AttackRegressionTest`（19）+ `CommandGuardTest`（8）+ `PathGuardTest`（14），含真实 symlink 逃逸、`echo >> authorized_keys` 需确认、审批异常即拒绝、误报用例（`rm -rf ./build` 只需确认、`ls -la` 免确认） |
| 7 解释器永不记住 | ✅ | Shell 侧**不再有任何持久放行**：原来 shell 弹框的 Always allow（会永久关闭确认）已删除，改为「Allow for this session」仅在内存里记住完全相同的命令，且解释器 / 元字符 / 嵌套 / 被拦截的命令不提供该选项。升级后首次启动会把 `confirmShellCommands` 重新打开一次（旧版 Always allow 留下的关闭状态无法与手动关闭区分）。MCP 的 SHA-256 指纹永久允许保持不变 |
| 8 PROVENANCE.md | ✅ | `src/desktop/PROVENANCE.md`，上游锁定 commit `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`（v1.14） |

真机三条验收用例仍待在 Windows 上手动确认。



## 构建

```bash
cd src/desktop
./gradlew :desktop:run          # 运行
./gradlew :desktop:packageMsi   # 打 MSI
./gradlew :desktop:packageExe   # 打 EXE
```

需要 JDK 17 或更高版本。
