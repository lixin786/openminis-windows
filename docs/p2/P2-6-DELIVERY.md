# P2-6 交付：备份 / 恢复 + 更新检查

规格：仓库根 `BACKUP-RESTORE.md`。本批**尚未在 Windows 真机验收**。

## 落点

| 文件 | 内容 |
|---|---|
| `core/Backup.kt` | 纯文件逻辑：收集、写 zip（临时 `.part` → 原子改名）、剥密钥、`inspect` 校验、`restore`（安全备份 → 解压到 staging → 改名旁置 → 移入，失败回滚）、旧备份列表 |
| `core/UpdateCheck.kt` | 一次 GET `releases/latest`，走 `DesktopHttp.newClient`（即设置里的代理），UA `OpenMinis-desktop/<version>`，数字段比较版本 |
| `core/AppRestart.kt` | 「立即重启」：重新执行当前进程命令（安装版即 `OpenMinis.exe`）；裸 `java` 无参数时不猜，退出并请用户手动重启 |
| `ui/BackupSettings.kt` | 设置 › 系统 › **备份** 页、高危确认卡、恢复后的重启弹窗、关于页「检查更新」与「备份文件」 |
| `ui/SettingsDialog.kt` / `App.kt` / `AppState.kt` / `Main.kt` | 挂接 |
| i18n ×3 | 49 条 |
| `BackupRestoreTest.kt` | 13 条测试 |

## 和规格对照的取舍

1. **包含项**：规格八项之外加了 `subagents.json`（子代理定义，属于用户配置）。
   **额外排除** `window.json`（机器相关）和 `mcp_trust.json`：恢复出来的 MCP 服务器需要重新信任才会启动，避免备份文件成为"自动执行命令"的载体。manifest 的 `excluded` 字段把排除项写明。
2. **settings.json 剥密钥**：递归清空所有 `apiKey` / `proxyPassword` 字符串值，保留 `*Store` 标记；JSON 解析失败就整个备份失败，**绝不原样拷贝**。
3. **恢复不带密钥的备份时，本机现有的 `secrets.json` / `remote-mcp-token.dpapi` 不动**。原因：同机恢复时不必重新输入密钥。恢复后设置里引用、本机却没有的密钥，会走 P3-1 的"解不开 → 列出并提示重新输入"路径。
   **验收第 5 条要在另一个 Windows 用户或另一台机器上测**，才能看到"密钥为空并提示"。如果你希望同机也清空，告诉我，改成把这两个文件也旁置即可。
4. **pre-restore 备份带密钥**（它只存在本机 `%APPDATA%\OpenMinis\backups\`，与 `secrets.json` 同处），所以回滚能连密钥一起回来。
5. **恢复后到重启前**：先停掉计划任务调度、手机互联服务和 MCP 进程（`freezeForRestore`），再交换目录。主窗口弹出不可关闭的「恢复完成，需要重启」，只有「立即重启」一个按钮，防止内存里的旧状态写回。
   正在回复、压缩或运行计划任务时，拒绝开始恢复。
6. 「从备份恢复」先 `inspect`，再自动做安全备份；损坏、非本应用、`format` 过新或含 `..` 路径（zip-slip）的包，在安全备份**之前**就被拒绝，数据完全不动。
7. 高危确认卡沿用 UI-REFRESH §1：错误色描边与条带、列出覆盖范围、文件名、来源时间、版本、条目数、是否带密钥。必须输入小写 `restore`（不翻译、两端空格忽略），主按钮用 error 色。
8. 旧数据管理放在关于页：列出 `pre-restore-*.zip` 和 `*.replaced-<时间>`，带大小；删除要二次点击「永久删除」；另有「打开备份文件夹」。
9. 更新检查：只在点击时联网。404 显示「还没有发布任何版本」，其余失败一句话提示，不弹窗、不重试。只打开 `https://github.com/` 开头的发布页链接。

## 验收清单（真机）

1. 默认备份用资源管理器打开，条目正确；在 zip 内搜模型 key、代理密码、手机令牌都搜不到
2. 勾「一并打包密钥」后出现 `secrets.json`、`remote-mcp-token.dpapi`，内容仍是密文
3. 恢复：`backups\pre-restore-*.zip` 已生成；不输入 `restore` 按钮是灰的；重启后会话、记忆、技能、计划任务、MCP 配置都在
4. 损坏或别的 zip → 一句话拒绝，数据不变，也不生成安全备份
5. 无密钥备份在另一台机器或另一用户下恢复 → 能启动，提示重新输入密钥
6. `format: 2` 的假 manifest → 拒绝并说明需要更新
7. 代理开 / 关各点一次「检查更新」，界面不卡
8. 三种语言文案齐全
