# P3-1 交付说明：API 密钥改走 Windows DPAPI

## 做了什么

- **加密方式：** `CryptProtectData` / `CryptUnprotectData`，CurrentUser 作用域（不带 `CRYPTPROTECT_LOCAL_MACHINE`），带固定的应用 entropy `OpenMinis/secrets/v1`，并设置 `CRYPTPROTECT_UI_FORBIDDEN`（出错时抛异常，不会弹出系统对话框）。调用走 JNA 5.15.0 的 `Crypt32Util`，只在 Windows 上加载。
- **存储：** `%APPDATA%\OpenMinis\secrets.json` 只存密文，格式是 `{"version":1,"entries":{"provider.ANTHROPIC.apiKey":{"scheme":"dpapi","blob":"<base64>"}}}`。`settings.json` 里 `apiKey` 永远是 `""`，只靠标记位 `apiKeyStore: "dpapi"` 表明密钥存在 secrets.json 里。代理密码也一并处理（`proxyPasswordStore`）。
- **写入顺序：** 先原子写 secrets.json，再原子写 settings.json。加密失败时两个文件都不写，异常往上抛，界面弹出提示“设置未保存：密钥加密失败（…）”。
- **首次启动迁移：** 读到明文密钥时，先保证内存里照常可用，再加密写入、清掉明文。迁移失败时 settings.json 原样不动，弹出提示，下次启动再试。
- **换机器或换用户：** 解不开的密钥会出现在启动提示里，也会在设置 › 模型里列出，对应服务商下面显示红字。这类密文**不会被静默删除**：保存其他设置时保留，重新输入密钥时只替换这一把，点“忘记无法解密的密钥”才会删除。secrets.json 损坏、无法解析时，重写前会先备份成 `secrets.json.damaged-<时间戳>`。
- **非 Windows：** 开发和测试构建行为不变，仍是明文，设置页显示原来那句“明文保存”的提示。
- **一个密钥没变就不重复加密**（每次加密都会生成新的密文，没有必要反复重写）。

## 测试（`SecretsDpapiTest`，8 个用例）

用可逆的假 DPAPI（按“用户”区分密钥，换个用户就解不开）覆盖以下场景：
- 迁移后明文被清掉，标记位和 secrets.json 的条目都正确。
- 保存时不写明文；密钥没变时不重复加密；清空密钥会同时删掉密文和标记。
- 换用户后解不开的密钥被列出、被保留；重新输入时只替换那一把；点“忘记”才会删除。
- secrets.json 损坏时会被报告；重写前先备份。
- 加密失败时不写任何文件。
- 没有 DPAPI 时保持旧格式。
- 新增文案三种语言齐全。
- **每个场景都会扫描临时目录里的所有文件**（包括 .tmp），确认找不到明文密钥，UTF-8 和 UTF-16LE 都查。

`realDpapiRoundTripOnWindows` 只在 Windows 上运行：用真 DPAPI 往返一次，验证密文里没有明文、篡改后的密文会解密失败，再用真 DPAPI 跑一遍迁移。

## 真机核对（建议）

1. 用旧版留下的 settings.json（里面有明文密钥）启动：能正常对话；settings.json 里 `apiKey` 为空、`apiKeyStore` 为 `dpapi`；secrets.json 里找不到密钥原文。
2. 重启后密钥仍然可用。
3. 换一个 Windows 账户（或复制数据目录到另一台电脑）启动：弹出提示，设置 › 模型列出这些密钥；secrets.json 没有被改动。重新输入一把密钥后只替换那一把，点“忘记”后其余的才会删除。
4. 运行 `gradlew test`：Windows 上会多跑 `realDpapiRoundTripOnWindows`。

## 未做

- 不用 Windows 凭据管理器（Credential Manager）。
- 手机互联令牌 `remote-mcp-token` 仍是明文文件，可以作为下一个小批次。
- 无法清除旧明文在磁盘扇区或卷影副本里的残留（DPAPI 管不到这一层）。
