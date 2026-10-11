# 计划任务无界面执行 — 交付说明（HEADLESS-SCHEDULER.md）

## 结构
| 文件 | 作用 |
|---|---|
| `scheduled/SystemTask.kt` | `CommandExecutor`（所有 `schtasks` 调用唯一出口，可注入）、`LaunchCommand`（解析启动命令）、`SystemTask`（注册/查询/删除） |
| `scheduled/SchedulerLock.kt` | `run-due-tasks.lock`（pid/owner/since，可识别过期锁）+ `headless-state.json`（checkedUpTo、上次 headless 运行、待提示的 needs-approval） |
| `scheduled/HeadlessRunner.kt` | `--run-due-tasks`：取锁 → 跑到点任务（单个串行、总超时 10 分钟）→ 记录 → 退出 |
| `scheduled/DesktopScheduler.kt` | `dueTasks()`（按持久化状态判断到点）、`runDue()`；进程内定时器加 `gate`（必须持锁）与 `heartbeat` |
| `security/Approvals.kt` | `HeadlessApprover`：一律拒绝、记录是哪一步，并立刻取消这次运行 |
| `ui/HeadlessSettings.kt` | 设置 › 计划任务 › 后台执行（开关、间隔、状态、移除按钮、提示条） |
| `Main.kt` | 带 `--run-due-tasks` 时先设 `java.awt.headless=true`，在建任何窗口之前分流并 `exitProcess` |

## 与规格不同之处（理由）
1. **注册用 `/XML` 而非 `/TR`**：`/TR` 的值里带引号的路径会被 Java 在 Windows 上的参数引号处理弄坏，且 `/TR` 有 261 字符上限。XML 把程序和参数分开写。`LogonType=InteractiveToken` 就等于 `/IT`，`RunLevel=LeastPrivilege` 表示不需要管理员，也不存密码。另外加了 `MultipleInstancesPolicy=IgnoreNew` 和 `ExecutionTimeLimit=PT15M` 作为防僵尸的兜底。`schtasks /Query /V /FO LIST` 中「登录模式」显示「仅交互式」，「要运行的任务」含 `--run-due-tasks`。
2. **启动程序**：fat jar 用 `<java.home>\bin\javaw.exe -jar <jar> --run-due-tasks`。安装版（jpackage）用 `OpenMinis.exe --run-due-tasks`，它本身就是无控制台的 GUI 启动器，没有单独的 javaw。dev 的 classes 目录无法注册，会给出提示。
3. **查询用 `/FO CSV /NH`**：按位置取「下次运行时间」，和 Windows 显示语言无关；LIST 的字段名会被本地化。
4. **到点判断**：after/interval 沿用持久化的 anchor（过期补跑一次）。calendar 用 `checkedUpTo`（应用定时器每分钟写一次，headless 每次写）、`lastFiredAt` 和 30 分钟宽限：关机好几天错过的整点**不补跑**，与上游规则一致。
5. **needs-approval 的严格程度**：第一次请求审批时，除了拒绝，还**取消整次运行**。模型拿不到第二轮，没法换一种写法绕过去。记录写明是哪一步以及命令行。
6. **同一管线**：headless 用的是同一个 `AppState`，`CommandGuard`/`PathGuard`/denylist 都不变，只把审批器换成 `HeadlessApprover`。如果用户自己在设置里关了 shell 确认，那么不需要审批的命令在后台照样会跑，和应用开着时一样。
7. **应用持锁**：应用的定时器只有拿到锁才触发；拿不到时每 15 秒重试，拿到后重新读 `scheduled_tasks.json`。如果有 headless 在应用打开期间写过会话，会重载会话列表，并提示 needs-approval。多开第二个实例也不会重复执行。

## 测试（`HeadlessSchedulerTest`）
- 假执行器：注册/查询/删除的完整命令行、XML 内容、临时 XML 已删除、反复开关只有同一个任务名、失败不重试
- 锁：互斥、能读出持有者、过期锁接管
- 到点判断、运行记录（headless / needs-approval / timeout）、接力任务、超时截断、定时器无锁不触发
- **端到端**（真实 `AppState` + 模拟模型服务器）：① 到点任务在后台跑完，会话里有回复，记录来源为 headless；② 模型调用 `echo pwned > out.txt` → 文件**没有**生成，模型只收到 1 次请求，记录为 needs-approval，并生成下次启动的提示；③ 应用持锁或开关关闭时直接退出，不发任何请求

## 待 Windows 真机验收
规格第七节 8 条，其中第 3 条（建 2 分钟后的任务 → 关应用 → 出现 headless 记录）和第 6 条（不出窗口、不抢焦点）必须在真机上看。

## 启动对账（验收后补充）

应用（有窗口的实例）每次启动在后台做一次 `schtasks /Query /XML` 对账，**不改设置本身**：

| 设置 | Windows 侧 | 动作 |
|---|---|---|
| 开 | 存在且 Command/Arguments/Interval 与当前一致 | 不动 |
| 开 | 不存在（被清理工具/更新删掉） | 重新注册 |
| 开 | 指向旧路径 / 间隔不同 / 导出无法解析 | `/Create /F` 覆盖 |
| 开 | 当前副本无法被 Windows 启动（开发 classes 目录） | 不动，记日志 |
| 开 | 注册被拒 | 只试一次，日志 + toast（三语 `headless.repair_failed`） |
| 关 | 存在 | 删除 |
| 关 | 不存在 | 不动 |

锁：除了模拟“残留文本但无 OS 锁”的用例，新增真实跨进程用例——子 JVM 取锁后被 `destroyForcibly` 杀掉（不释放、不清理），下一个持有者识别为过期锁并接管，`staleFrom` 记录被杀进程的 pid。

## 修复：headless 退出前等待未完成的写盘

AppState 的会话/记录写盘是挂在 scope 上的异步 IO 任务；headless 结束时直接 `scope.cancel()` 可能把尚未执行的写盘取消，出现「运行记录 ok、会话文件里没有回复」。新增端到端用例后顺序变化暴露了这个竞态。`HeadlessRunner` 现在在 cancel 之前 drain scope 子任务（上限 10 s，超时记日志）。HeadlessSchedulerTest 连跑 5 次全绿。
