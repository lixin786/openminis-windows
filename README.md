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

## 构建

```bash
cd src/desktop
./gradlew :desktop:run          # 运行
./gradlew :desktop:packageMsi   # 打 MSI
./gradlew :desktop:packageExe   # 打 EXE
```

需要 JDK 17 或更高版本。
