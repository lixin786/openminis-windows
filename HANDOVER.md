# OpenMinis Windows 版 —— 交接文档

> 写于 2026-10-12 深夜。上一任主智能体（ZCode 侧）按用户要求停止开发，交接给 DeepSeek Harness。
> 本文是**自包含**的：假设读者没看过任何历史对话，也能接着往下做。

---

## 0. 一句话现状

`OpenMinis` 的 Windows 桌面移植已完成 P0/P1/P2 的大部分（含数学公式渲染、备份恢复、无界面计划任务、手机互联 MCP 服务端、沉浸式窗口、打包），**当前代码分支全量测试 733/0/0/0**，真机验收过的部分见 §4。剩余项见 §5。

---

## 1. 项目与分工

| | |
|---|---|
| **目标** | 把 Android 应用 OpenMinis 移植成 Windows 桌面版（Compose Multiplatform Desktop），保留命令行/文件/记忆/技能/子代理/计划任务等智能体能力，中文本地化，可出 MSI/EXE |
| **上游** | `github.com/OpenMinis/OpenMinis`，锁定提交 `b4c0661d…`（v1.14），GPL-3.0 |
| **技术栈** | Kotlin 2.1.0 / Compose Multiplatform 1.7.3 / JVM 17 / Gradle 8.11，单模块 `:desktop` |
| **目标机** | Windows 11 中文版，2560×1600 @200% 缩放（逻辑 1280×800，工作区 1280×752），本机用户名 `liNxI` |

**三方分工（重要）**

- **用户**：定方向、拍板取舍、提供机器与凭据（凭据只走他自己的浏览器，从不经聊天）。
- **Hark**（`hark.com` 云电脑智能体，x86 Linux 虚拟机）：执行方，写代码、跑测试、推送分支、打 jar。
- **上一任主智能体**（ZCode，已退出）：编排 + 验收。拆需求派给 Hark、拉代码在真机复测、把结论与缺陷退回 Hark、决定放行。
- **你（接手方）**：可以同时扮演编排与执行。**注意**：Hark 那边可能还有一批在途任务（见 §5 末尾），接手时先和用户确认是继续用 Hark 还是全部自己来。

---

## 2. 代码与文档在哪

### 仓库

| 位置 | 说明 |
|---|---|
| 本地工作副本 | `D:\ZCode\projects\openminis-test\repo`（当前 checkout 在 `ui-batch` 的尖端，detached） |
| `origin/ui-batch` | **代码交付分支**，Hark 往这里推，最新 `ee940e9` |
| `origin/main` | 规格与验收文档分支（上一任主智能体写的），含 `UI-REFRESH.md`、`BACKUP-RESTORE.md`、`HEADLESS-SCHEDULER.md`、`IMMERSIVE-WINDOW.md`、`VERIFY-batch11.md`、`README.md` |
| `origin/spike/p2-4-math` | 数学渲染探路分支（历史） |
| 远端 | `github.com/lixin786/openminis-windows`（fork） |

Hark 的 GitHub 授权是**它自己的 GitHub App**，已收窄到这一个仓库；不要给它别的凭据。

### 关键文档（都在仓库里，读代码前先读这些）

| 文件 | 内容 |
|---|---|
| `src/desktop/PORTING.md` | **总进度表**：上游文件→桌面端对应关系、行为差异、安全门禁逐条状态、P0/P1/P2/P3 未做项。**接手第一站** |
| `src/desktop/README.md` | 怎么构建、怎么跑、测试清单 |
| `src/desktop/PROVENANCE.md` | 上游来源、许可证义务、第三方组件 |
| `IMMERSIVE-WINDOW.md`（main） | 自绘标题栏/沉浸式窗口的规格、验收项、**已修缺陷与真机实测数值**、已知限制 |
| `BACKUP-RESTORE.md`（main） | 备份/恢复 + 更新检查的可执行规格 |
| `HEADLESS-SCHEDULER.md`（main） | 无界面计划任务（schtasks + `--run-due-tasks`）规格 |
| `MCP-SERVER.md`（ui-batch） | 手机互联（桌面端当 MCP **服务端**）的传输层/安全规格 |
| `docs/**`（ui-batch） | Hark 每批的交付说明 |

---

## 3. 构建与测试（**先看这一节的坑**）

### ⚠️ 本机 JAVA_HOME 是坏的

用户环境变量 `JAVA_HOME=D:\我的世界Java\` 指向一个**不存在的目录**。直接跑 `gradlew` 会立刻报
`ERROR: JAVA_HOME is set to an invalid directory`，而且如果命令带了管道（`| tail`），**退出码会被管道掩盖成 0**，看起来像"跑完了没输出"。
**必须显式指定 JDK**：

```bash
cd /d/ZCode/projects/openminis-test/repo/src/desktop
JAVA_HOME='D:\ZCode\tools\android-build-tools\jdk\jdk-17.0.20.1+1' \
  ./gradlew :desktop:windowsUberJar :desktop:test --offline --console=plain
```

- Gradle 根目录是 `src/desktop`（`settings.gradle.kts` 里 `include(":desktop")`，`projectDir = app`）。模块名是 `:desktop`，**不是** `:app`。
- `--offline` 是必要的：本机 Maven 直连 `repo.maven.apache.org` 常握手失败；依赖已在 Gradle 缓存里。
- 产物：`src/desktop/app/build/libs/OpenMinis-windows-x64-1.14.0.jar`（uber jar，启动即用）与 `desktop-1.14.0.jar`。
- **打包前先关掉正在运行的应用**，否则 uber jar 被占用，任务报 `Unable to delete file`。

### 测试结果怎么数

```bash
cd /d/ZCode/projects/openminis-test/repo/src/desktop/app/build/test-results/test
python - <<'PY'
import glob, xml.etree.ElementTree as ET
t=f=e=s=0; bad=[]
for p in glob.glob("*.xml"):
    r=ET.parse(p).getroot()
    t+=int(r.get("tests",0)); f+=int(r.get("failures",0)); e+=int(r.get("errors",0)); s+=int(r.get("skipped",0))
    if int(r.get("failures",0))+int(r.get("errors",0)): bad.append(r.get("name"))
print(f"tests={t} failures={f} errors={e} skipped={s}"); print("失败套件:", bad or "无")
PY
```

**基线：`ee940e9` = 733 tests / 0 failures / 0 errors / 0 skipped（79 个套件）。** 数字对不上就是有问题。

### 跑起应用

```bash
"D:\ZCode\tools\android-build-tools\jdk\jdk-17.0.20.1+1\bin\javaw.exe" -jar \
  "D:\ZCode\projects\openminis-test\repo\src\desktop\app\build\libs\OpenMinis-windows-x64-1.14.0.jar"
```

数据目录 `%APPDATA%\OpenMinis\`：`settings.json`、`conversations/<id>.json`、`window.json`、`skills/`、`backups/`、`logs/YYYY-MM-DD.log`、`secrets.json`（DPAPI 密文）、`remote-mcp-token.dpapi`。
工作区默认 `C:\Users\liNxI\OpenMinis\workspace`（可改），技能也可放 `<workspace>\.skills\<name>\SKILL.md`。

---

## 4. 已验收通过（可用，附证据）

| 模块 | 状态 | 证据 / 备注 |
|---|---|---|
| P0 全批（上下文压缩、图片/附件输入、模型回退链、记忆检索） | ✅ | `PORTING.md` P0 表；单测 |
| 安全门禁 7 条（denylist、元字符、只读白名单、路径硬约束、fail-closed、攻击回归、解释器不记忆） | ✅ | `PORTING.md` 门禁表逐条；`AttackRegressionTest` 19 + `CommandGuardTest` 8 + `PathGuardTest` 14 |
| P1-1 MCP **客户端**（stdio） | ✅ | `PORTING.md`；HTTP/OAuth 传输未做（见 §5） |
| P1-2 子代理 | ✅ | 设置页有：启用开关、并发数、委派深度、默认/轻量模型、代理列表 |
| P1-3 技能：导入 UI、加载、列表、逐个开关 | ✅ | 真机实测：把 `SKILL.md` 放到 `<workspace>\.skills\`，技能页显示「共 1 个技能」+ 名称/描述 + 开关 + 打开文件夹/删除；**再发一条消息问模型，模型答复里确实出现了该技能名**（说明已进系统提示词） |
| P1-4 计划任务（界面内 + 无界面） | ✅ | `schtasks /XML` 注册、`--run-due-tasks` 无界面跑、权限最小化；真机验过 |
| P2-4 数学公式渲染（三步全批） | ✅ | JLaTeXMath；`$…$`/`$$…$$`/`\(…\)`/`\[…\]`、金额排除、代码块跳过、失败回退、深色主题；设置页「渲染数学公式（LaTeX）」开关（默认开，三语）；系统提示词按开关注入 `MATH_HINT`。提交 `48d665d` → `1e869f5` →（跨块缺陷修复） |
| P2-5 三语 i18n（en / zh-CN / zh-TW） | ✅ | `I18nTest`；设置页语言可切 |
| P2-6 备份 / 恢复 + 更新检查 | ✅ | 真机端到端：创建备份（默认**不打包密钥**，`settings.json` 里 `apiKey` 剥空但保留 `apiKeyStore` 标记，zip 内无密钥文件）；坏 zip / 缺 manifest 的 zip 均被拒且数据未动；恢复需**输入 `restore`** 才放行；自动 pre-restore 快照确实含恢复前状态；恢复后 23 个会话与备份字节一致；本机 DPAPI 密钥未被动。更新检查：离线时一句话失败提示（无弹窗无重试），配代理后拿到 GitHub 真实 404 → 「还没有发布任何版本。」 |
| P3-1 / P3-1b DPAPI 密钥与手机令牌 | ✅ | `secrets.json`、`remote-mcp-token.dpapi`；设置页只显示掩码 |
| 手机互联（桌面端 MCP **服务端**，13.1–13.3） | ✅ | 真机 curl 验过：绑定局域网私有地址、鉴权先于动词、会话自愈、5 个只读工具、junction/UNC/ADS/denylist 约束、锁定 429、踢掉 403。**注意方向**：手机端没有入站服务端，桌面端才是服务端 |
| 沉浸式窗口（自绘标题栏） | ✅ | 拖拽（+150/+100 精确）、八向缩放（四边四角精确）、520dp 最小尺寸、双击最大化到工作区并精确还原、`window.json` 持久化、Alt+Space 窗口菜单、折叠应用菜单、F2 重命名（落盘）、右键菜单、Ctrl+F 搜索（含正文匹配） |
| 窗口几何缺陷修复 | ✅ | 提交 `ce069e2` → `b4b3a26` → `255fd0f`（+ `ee940e9` 文档更正）。真机复测：外部 `SetWindowPos` 六次以上不再崩、`1800x2400` 钳到 `1800x1728`、`1200x1200` 宽钳到 `1520`、`1800x65535` 不崩不最小化；整轮零崩溃报告 |
| 打包 MSI / EXE | 🟡 | `packageMsi` / `packageExe` 在中文 Windows 上验过（jpackage 元数据须纯 ASCII，否则 `Input length = 1`），MSI 68.9MB / EXE 69.5MB，静默安装不需管理员，卸载保留 `%APPDATA%\OpenMinis`。**但那是更早提交打的包**，不含数学渲染与几何修复，需要重打（见 §5） |

---

## 5. 还没做 / 待办

### PORTING.md 里明确未做的

| 编号 | 项 | 状态 | 备注 |
|---|---|---|---|
| P2-1 | 内置浏览器工具（JCEF / Playwright / 系统 Chrome CDP） | ⬜ | 上游是 WebView。**先做选型再实现**：要带实测体积与打包影响；安全上要定"默认拦私网/回环、按域名审批或白名单、体积/时间上限、默认关的开关" |
| P2-2 | 图像 / 音频生成 UI + vision groups | ⬜ | provider 代码已在，`VisionGroupResolver` 是 stub |
| P2-3 | OAuth 登录（Claude Code / Codex / Copilot） | ⬜ | Copilot 设备码流、OpenRouter PKCE 用公开值可行；**Claude Code / Codex 需要私有 client id，公开镜像里没有，做不了** |
| P1-3 后半 | 按会话的技能覆盖 | ⬜ | 导入 ✅；按会话 MCP 覆盖已于批次 9 完成，技能覆盖要对齐它 |
| P1-1 后半 | MCP **客户端** HTTP / OAuth 传输 | ⬜ | `url` 型条目目前只保存不启动 |
| P3-2 后半 | 代码签名 | ⏸️ | **用户明确决定不做**，不要主动提 |
| — | 发布页 / 官网 | ⏸️ | **用户明确说了不要做**，不要再提 |

### 在途（上一任刚派给 Hark，可能还没回）

一条消息里派了三件事，Hark 已回「已阅读 / 正在使用我的电脑」，**结果未知**：

1. 实现 **按会话的技能覆盖**（小件）。
2. 给 **P2-1 浏览器**做**选型报告**（不写实现）：JCEF / Playwright / 系统 Chrome CDP 各自的实测体积与打包影响 + 安全取舍，等拍板。
3. 把 `PORTING.md` 里 P2-6 与 P1-4 的「needs real-machine check」改成已验（这两项真机都已验过，证据见 §4）。

接手时请先去 Hark 那看一眼结果（怎么收发消息见 §7），再决定继续用它还是自己来。

### 我这次没验完的（诚实清单）

| 项 | 情况 |
|---|---|
| 计划任务**图形窗口** | 打不开。侧栏那两个图标按钮（计划任务/设置）和 Compose 弹出菜单项对我的**合成点击**不响应，而同一窗口里会话列表、标题栏按钮、设置页各处又是响应的。**需要人工用真鼠标点一次**：点侧栏日历图标或 ☰ → 定时任务… |
| 技能「导入粘贴内容」按钮 | 同上，合成点击点不动（我试了 4 次，含悬停后点击）。**需要人工点一次**确认。加载/列表/开关/模型可见都已验过 |
| MSI / EXE 重打 | 见上表：手上那个包是旧提交产物 |
| 手机端联调 | 用户决定跳过（要手机版也构建） |

---

## 6. 真机验收工具箱（直接复用，别重写）

全部在 `D:\openminis-test\shots\`（PowerShell，全部 DPI-aware、1:1 物理像素）：

| 脚本 | 用途 |
|---|---|
| `windows.ps1 [-ProcId N]` | 枚举可见顶层窗口：句柄/pid/类名/矩形/标题，`*` 标记前台 |
| `setsize.ps1 -Handle H -Left X -Top Y -Cx W -Cy H` / `-Read` | 读/写窗口物理矩形（**参数名安全的正确写法**） |
| `fg.ps1 -Hwnd H` | 抢前台：ALT 解锁 + `AttachThreadInput` 兜底，绕过 Windows 前台锁 |
| `click.ps1 -X -Y [-Action click\|dblclick\|hover]` | 合成点击（`mouse_event`） |
| `drag.ps1 -X1 -Y1 -X2 -Y2` | 平滑拖拽（分步移动，Compose 能识别） |
| `scroll.ps1 -X -Y -Notches N` | 滚轮（负=向下） |
| `typing.ps1 -Text "abc" [-Enter] [-Esc]` | 合成打字（**仅 ASCII**，中文请用剪贴板） |
| `snap.ps1 -Chord left\|right\|up\|down` | 发 Win+方向键（系统吸附/最大化） |
| `filedlg.ps1 -Hwnd <#32770> -Path <路径>` | 原生文件对话框：`WM_SETTEXT` 填路径 + `WM_COMMAND(IDOK)`，绕开焦点 |
| `capture.ps1 -Hwnd H -Out x.png` | 按句柄抓 PNG（屏幕区域截图，**会被浮在上面的窗口挡住**） |
| `drive-skill-import.ps1` | 示例：把一串 UI 操作用**单进程**跑完（先 `Foreground` 再连续点击） |

`D:\openminis-test\fixtures\` 里有我造样本：`math2.md` / `math3.md`（公式渲染回归样本）、`inject.py`（把一段 md 注入成一条会话）、`demo-skill/SKILL.md`、`minimal-skill.md`。

### 三条硬规则（违反必踩坑）

1. **坐标永远按"当下"几何算**。`capture.ps1` 的 PNG 是 1:1 物理像素；CUA（`agent.computerUse`）的栅格是 1280 宽，ratio = 窗口宽/1280。窗口一变（最大化/改尺寸/多一行内容），**之前所有坐标作废**。我因为这条连错三次。
2. **应用常常不是前台**（用户机器上还有别的应用在抢焦点）。合成点击若在非前台窗口上，第一次会被当成"激活点击"吃掉 —— 先 `fg.ps1`，或者**连点两次**；更稳的做法是把整串操作用一个脚本在同进程里跑（先置前后立刻点）。
3. **中文输入只能走剪贴板**：`Set-Clipboard -Value (Get-Content -Raw -Encoding UTF8 文件)` 再发 Ctrl+V。`Get-Content` 不加 `-Encoding UTF8` 会按 GBK 解码成乱码。

### 其他坑（都真实踩过）

- **PowerShell 变量名大小写不敏感**：`$h = [IntPtr]$Hwnd` 会**静默覆盖** `param([int]$H)`。我因此给 `SetWindowPos` 传了句柄截断值 65535，误报了一个不存在的产品缺陷，让协作方白改两轮。脚本参数别用单字母。
- **PowerShell 5.1 读 .ps1 默认按 ANSI**（无 BOM 的 UTF-8 文件里中文字面量会变乱码）。脚本里别写中文匹配字符串，或存成带 BOM。
- **抓图会被遮挡**：`capture.ps1` 是屏幕区域截图，先 `fg.ps1` 再抓。
- **关窗口用 Alt+F4 干净退出**（`Stop-Process -Force` 不会写 `window.json`）。
- 合成 `Win+Down` 在**非最大化**窗口上等于**最小化**，Windows 还会记住这个显示状态，导致下次启动是最小化 —— 别把它当缺陷。
- 应用 `%APPDATA%\OpenMinis` 里现在有 **4 份 `hs_err_pid*.log`**：那是 `ce069e2` 那版尺寸上限问题留下的历史证据（Skiko 建 32000 高的 D3D 表面越界），已修复，文件可删。

---

## 7. 怎么继续和 Hark 协作

- 界面：**应用内浏览器**打 `hark.com/chat`（上一任留下的标签页）。Hark 是聊天式智能体，不是 API。
- 发消息：填进 `textbox "消息输入"`，点 `button "发送消息"`，**然后 `tab.reload()` 确认真的投递了**（它会静默丢掉"正在回复时"收到的消息）。
- 让它交付时**附上新 jar 和提交号**，并说明"哪几条真机要看的"。
- 收到它说"已修/已完成"后：`git fetch --depth 1 origin ui-batch` → `git checkout -f FETCH_HEAD` → 按 §3 跑全量 → 按 §6 真机复测 → 把**实测数值**和**复现步骤**退回给它。
- **它说"测试全绿"不等于没问题**：上一任抓出过 3 次"它绿我真机红"（zh-CN 下 I18n 全局状态、锁接管竞态、以及崩溃/尺寸回归）。
- **安全红线（用户定的，必须遵守）**：只派**公开**任务；**绝不给**私人账号/密钥/个人信息；不做批量注册、不绕验证码；用完即走。凭据只能走用户自己的浏览器（Hark 的 connected-accounts 页），**不要贴进聊天**。GitHub 用 **Hark 自己的 App** 且已收窄到单个仓库。

---

## 8. 建议的下一步顺序

1. 去 Hark 看 §5 在途那批的结果；把按会话技能覆盖收尾（小、有明确验收标准）。
2. 拍板并实现 **P2-1 浏览器**（选型报告到手后决定；注意安装包体积与"默认关 + 按域名审批"的安全取舍）。
3. 从**当前提交重新打一次 MSI/EXE** 并重跑安装/卸载验收（这样手上才有与代码一致的安装包）。
4. 人工点两次把两个"合成点击点不动"的界面确认掉：计划任务窗口、技能导入按钮。
5. 再考虑 P2-2 图像/音频生成 UI、MCP 客户端 HTTP/OAuth；P2-3 只做 Copilot / OpenRouter 那部分。

---

## 9. 遗留物清单（我留下的东西）

| 路径 | 说明 | 建议 |
|---|---|---|
| `D:\openminis-test\repo` | 代码工作副本（ui-batch 尖端） | 保留 |
| `D:\openminis-test\shots\*.ps1` | 真机验收工具箱 | **保留复用** |
| `D:\openminis-test\fixtures\` | 公式/技能测试样本、`inject.py` | 保留复用 |
| `D:\openminis-test\settings-before-proxy.json` | 代理实验前的 settings 备份 | 可删 |
| `C:\Users\liNxI\OpenMinis\hs_err_pid*.log` ×4 | 历史崩溃证据 | 可删 |
| `%APPDATA%\OpenMinis\backups\pre-restore-*.zip` | 我做恢复测试时的自动快照（含 DPAPI 密文，仅本机本用户可解） | 可在「设置 › 关于 › 备份文件」里一键删 |
| `D:\wiki\experiences\2026-10-12-量具自证.md` | 上一任把"量具先自证"的教训归档进知识库 | 保留 |

**我已经清理掉的**：应用进程（0 个）、我放的测试技能（工作区 `.skills` 已移除）、我创建的测试会话（会话数回到 23）、`window.json` 复位成悬浮几何、代理设置回直连、数学渲染开关回默认开。

不构成投资建议；股市有风险，投资需谨慎。（本项目与投资无关，此句仅因交接文档可能被同一套流程复用而保留。）
