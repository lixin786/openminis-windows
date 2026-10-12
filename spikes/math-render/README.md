# P2-4 数学渲染探路（spike，不是产品代码）

独立 Gradle 工程，不被 `src/desktop` 引用。版本与产品一致：Compose 1.7.3、markdown-renderer 0.28.0，另加 JLaTeXMath 1.0.7。

运行（Windows x64，Java 17+）：`java -jar math-render-spike-windows-x64.jar`
构建：`../../src/desktop/gradlew -p . windowsJar` → `build/libs/`；测试：`../../src/desktop/gradlew -p . test`（`-Dspike.shots=<dir>` 输出 100%/200% 截图）

## 接法
- `$$…$$` / `\[…\]` 先切成独立块，居中显示，超宽时横向滚动。
- 行内 `$…$` / `\(…\)` 换成私用区占位符；用 `markdownComponents(paragraph = …)` 换掉段落组件，并用 annotator 把占位符变成**每个公式各自尺寸**的 `InlineTextContent`。库自带的行内图片位**一个段落只用一个尺寸**，放不下大小不同的公式，所以没用它。
- 基线：占位宽 = 公式宽、高 = 基线以上部分，`AboveBaseline` 对齐；基线以下部分画在占位框外（Compose 不裁剪），不会把行往上顶。
- 位图按 `LocalDensity` 的物理像素渲染，1:1 绘制（不重采样）。
- 字面量 `$` 一律转义后再交给 markdown：intellij-markdown 的 GFM 自带 `$` 数学节点，渲染库会把它整段丢掉。

## 已知
- JLaTeXMath 对**不完整**输入很宽容（`\frac{a}{`、`x^`、`{a` 会照样画出来），只有未知命令、`\left` 不配对、环境未闭合、多余 `}` 这类才报错并回退。
- 标题、列表项、表格里的公式在 spike 中显示原文；正式接入时再替换对应组件。
