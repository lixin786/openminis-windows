# 沉浸式窗口第一批 · 交付说明

- 规格：仓库根目录 `IMMERSIVE-WINDOW.md`（基于 d4628e0，已合入 `ui-batch`）
- 分支：`ui-batch`；提交号见 `git log`（提交信息以 `immersive(batch1)` 开头）
- 全量测试：626/0/0/0（原 612 条，新增 `ImmersiveWindowTest` 14 条）
- 第二批（DWM 圆角与阴影）没有做；失焦变暗和菜单折进标题栏的成本很低，第一批已一并完成

## 改了什么

| 文件 | 内容 |
|---|---|
| `core/WindowGeometry.kt`（新） | 纯函数与状态机：8 向缩放的数学（`WindowGeometry.resize`）、宿主显示器的选择、`PlacementMachine`（浮动几何与最大化分开存储，最大化时忽略拖动和缩放） |
| `ui/WindowChrome.kt`（新） | 标题栏拖拽区 `titleBarDrag`（拖动、双击、右键）、`CaptionButtons`（46dp 宽，关闭键 hover 时用错误色底，带 tooltip）、`TitleBarMenu`、`ImmersiveFrame`（1px 描边加 8 个缩放区：边 5dp、角 10dp）、`DialogTitleBar`、`chromeKey`（Alt+F4 / Alt+Space）、`AppMenuButton` |
| `ui/AppMenus.kt`（新） | 文件/视图/对话菜单抽成数据。系统标题栏模式渲染成 Swing `MenuBar`，沉浸式模式渲染成标题栏里的菜单按钮，两边内容同源 |
| `Main.kt` | 主窗口 `undecorated = immersive`；`key(immersive)` 切换时重建窗口；位置持久化改由 `PlacementMachine` 负责；沉浸式模式不建 `MenuBar` |
| `SettingsDialog.kt` / `ScheduledTasksWindow.kt` | `DialogWindow(undecorated = immersive)` 加上 `DialogTitleBar`（标题即拖拽区，右侧只有关闭）；Esc 照旧关闭；Alt+F4 / Alt+Space |
| `App.kt` | 顶栏即标题栏：空白的会话标题区和侧栏 "OpenMinis" 字样区可拖；最右是三个窗口控制键；最左多一个菜单按钮 |
| `Settings.kt` + 设置 › 外观 › 窗口 | `immersiveWindow` 默认开；旧的 settings.json 读出来也是开 |
| `WindowPlacement.kt` | 兼容旧 window.json：旧版在最大化时把最大化后的尺寸存成了浮动尺寸，读到「最大化且几乎占满屏幕」的记录时改用默认浮动尺寸 |
| i18n 三语 | `window.*`、`appearance.section.window`、`appearance.immersive(_hint)` |
| README | 「沉浸式窗口第一批」一节，含已知限制 |

## 关键设计

1. **最大化由应用自己做，不调用 `MAXIMIZED_BOTH`。** undecorated 的 JFrame 用 AWT 最大化会盖住任务栏。现在「最大化」= 把窗口设为宿主显示器的工作区（屏幕减去任务栏），宿主取窗口中心所在的那台。最大化前的浮动几何单独保存，还原时一像素不差地回去。
2. **window.json 只存浮动几何加 maximized 标志。** 最大化时不写回最大化后的尺寸；窗口处于最小化时（Windows 会把它停在 -32000,-32000）也用浮动几何。系统标题栏模式下由 OS 负责最大化，浮动几何在移动或缩放停稳 300ms 后才记录，避免 OS 最大化过程中的尺寸事件被误记成浮动尺寸。
3. **拖动和缩放都以手势起点为基准，用屏幕绝对坐标计算。** 鼠标位置取 AWT 事件的 `locationOnScreen`，不是窗口内坐标，所以拖左边或上边时窗口移动不会造成抖动。缩到最小尺寸时，对边保持不动。最小尺寸沿用 `WindowPlacements.MIN_W/MIN_H`，屏幕比它还小时按屏幕计算。
4. **切换开关时重建窗口。** AWT 不能给已经显示的窗口改装饰，所以三个窗口都套 `key(immersive)`。浮动几何和最大化状态在重建前后一直保留；从沉浸式最大化切到系统标题栏时，窗口会变成 OS 的最大化。
5. **菜单栏不叠两行。** Compose 的 `MenuBar` 是 Swing 的 JMenuBar，在 undecorated 窗口里仍然会独占一行，所以沉浸式模式不创建它，改用标题栏左侧的菜单按钮（☰），三组菜单分节列出。它原有的快捷键（Ctrl+Shift+K、F2、Ctrl+F）在沉浸式模式下改由窗口按键处理器负责；系统标题栏模式下仍由 MenuBar 处理，不会触发两次。
6. **小屏下顶栏不溢出。** 顶栏改用自定义布局 `LeadThenFill`：模型药丸和思考 chip 先缩，拖拽区至少保留 56dp，右侧按钮和窗口控制键始终在屏幕内。顶栏宽度小于 820dp 时，药丸隐藏服务商徽标，并隐藏上下文读数。
7. **Alt+F4 双保险。** 系统处理一次，`chromeKey` 再处理一次；`quit()` 是幂等的，不会重复执行。Alt+Space 打开应用自绘的窗口菜单。
8. **失焦变暗（成本低，已做）：** 窗口失焦时标题、控制键和描边降到 60% 不透明度。

## 测试（ImmersiveWindowTest）

- 8 个方向各自改动正确的边；8 种光标互不相同；最小尺寸生效且对边不漂移；屏幕小于最小尺寸时按屏幕计算
- 宿主显示器的选择（窗口中心所在的那台，否则重叠面积最大的那台）
- 最大化只占宿主工作区，还原精确；最大化期间拖动和缩放都被忽略；拖动后再最大化、还原，仍回到拖动后的位置
- 启动时恢复为最大化：先放在浮动位置再最大化，还原后回到保存的尺寸
- 系统标题栏模式：OS 最大化之后迟到的尺寸事件不会污染浮动几何
- 旧 window.json 兼容，以及 window.json 读写往返
- 1366×768@150%（工作区约 910×472）：主窗口和两个对话框都在屏幕内，最大化等于工作区
- 开关默认开，旧 settings.json 也读出开
- 菜单数据包含原 MenuBar 的全部 17 项，顺序一致
- 静态检查三个窗口：`undecorated = immersive`、`DialogTitleBar`、`ImmersiveFrame`、`chromeKey`、Esc，以及沉浸式模式下没有 MenuBar
- 离屏渲染主窗口：910×472 和 1280×800 两种尺寸，最大化与非最大化各一次，都不崩溃

## 截图（第 11 条）

当时离屏渲染了 1280×800 和 910×472 两种尺寸，亮暗两套主题，外加设置窗口的标题行。这些图后来已从仓库删除，以后截图一律单独作为附件发送，不再进仓库或补丁。真机验收（2026-10-11）已确认外观：系统标题栏去掉了，最小化、最大化、关闭三个按钮画在应用顶栏右侧，菜单已折叠，不再占两行。

## 真机重点核对（离屏渲染验证不了的部分）

- 在真机上拖动和缩放的手感、光标形状；跨显示器时最大化落在哪台
- 点任务栏图标最小化（AWT undecorated 窗口可能不支持，已写进 README 已知限制）
- Alt+F4 / Alt+Space
- 切换开关后三个窗口的重建
