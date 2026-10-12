package spike.math

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

val SAMPLE = """
### 1 · 行内公式与中文混排
设直角三角形满足 ${'$'}a^2+b^2=c^2${'$'}，当 ${'$'}x \to 0${'$'} 时 ${'$'}\frac{\sin x}{x} \to 1${'$'}。求和 ${'$'}\sum_{i=1}^{n} i = \frac{n(n+1)}{2}${'$'}，积分 ${'$'}\int_0^1 x^2\,dx = \frac{1}{3}${'$'}，下标 ${'$'}x_{k+1}${'$'} 与上标 ${'$'}e^{i\pi}${'$'}，还有公式里的中文 ${'$'}\text{速度} = \frac{\text{路程}}{\text{时间}}${'$'}。这段话故意写得比较长，让它自动换行到第二行、第三行，方便看行高有没有被公式撑开、基线是否和汉字对齐。

对照段落：设直角三角形满足 a²+b²=c²，当 x → 0 时 sin x / x → 1。求和 1+2+…+n = n(n+1)/2，积分 x² 从 0 到 1 等于 1/3，下标 x_k+1 与上标 e^iπ，还有公式里的中文 速度 = 路程 / 时间。这段话故意写得比较长，让它自动换行到第二行、第三行，方便看行高有没有被公式撑开、基线是否和汉字对齐。

只有矮公式的一行：变量 ${'$'}x${'$'}、${'$'}y${'$'} 和 ${'$'}a+b${'$'} 应该与汉字完全同一基线，行高不变。

### 2 · 块级公式（居中）
$$ E = mc^2 $$
$$ \int_{-\infty}^{\infty} e^{-x^2}\,dx = \sqrt{\pi} $$
$$ f(x) = \begin{cases} x^2, & x \ge 0 \\ -x, & x < 0 \end{cases} \qquad A = \begin{pmatrix} 1 & 2 \\ 3 & 4 \end{pmatrix} $$

### 3 · 超宽公式（应横向滚动）
$$ (a+b)^{10} = a^{10} + 10a^9b + 45a^8b^2 + 120a^7b^3 + 210a^6b^4 + 252a^5b^5 + 210a^4b^6 + 120a^3b^7 + 45a^2b^8 + 10ab^9 + b^{10} $$

### 4 · 故意写错（应回退为原文 + ⚠，不崩溃）
块级：
$$ \begin{matrix} a & b $$
行内：未知命令 ${'$'}\foo{x}${'$'}，不配对的 ${'$'}\left( x${'$'}，之后的文字应照常显示。

### 5 · 金额与转义（不应误判）
价格 ${'$'}5 与 ${'$'}x+1${'$'} 同时出现时，只有后者是公式。

从 ${'$'}5 涨到 ${'$'}10，区间 ${'$'}5-${'$'}10，都不是公式。

转义 \${'$'}x\${'$'} 不渲染；行内代码 `${'$'}x^2${'$'}` 不渲染；代码块也不渲染：

```
cost = ${'$'}5; total = ${'$'}${'$'}x${'$'}${'$'}
```
""".trimIndent()

@Composable
fun SpikeContent(showBaselines: Boolean, onToggle: ((Boolean) -> Unit)?) {
    val density = LocalDensity.current
    val sample = remember(density.density) {
        val px = 16f * density.density
        (MathRenderer.render("E = mc^2", px, 0xFF000000.toInt(), display = true) as? MathRenderer.Result.Ok)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append("屏幕缩放 ${"%.2f".format(density.density)}×（${(density.density * 100).toInt()}%）· 字号缩放 ${"%.2f".format(density.fontScale)}")
                    append(" · 中文回退字体：${MathRenderer.cjkFont ?: "未找到"}")
                    append(" · 公式字号 ×${MathRenderer.SIZE_FACTOR}")
                    sample?.let { append(" · E=mc² 位图 ${it.widthPx}×${it.bitmap.height} px（1:1 绘制，不缩放）") }
                },
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
            )
            if (onToggle != null) {
                Checkbox(showBaselines, onToggle); Text("显示基线/行框", style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(Modifier.fillMaxSize()) {
            Pane("浅色", lightColorScheme(), showBaselines, Modifier.weight(1f))
            Pane("深色", darkColorScheme(), showBaselines, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Pane(title: String, scheme: ColorScheme, showBaselines: Boolean, modifier: Modifier) {
    val heights = remember { mutableStateMapOf<String, List<Int>>() }
    MaterialTheme(colorScheme = scheme) {
        Surface(modifier.fillMaxHeight(), color = scheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.primary)
                CompositionLocalProvider(LocalShowBaselines provides showBaselines, LocalLineReport provides { k, h -> if (heights[k] != h) heights[k] = h }) {
                    MathMarkdown(SAMPLE)
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().background(scheme.surfaceVariant).padding(8.dp)) {
                    Text("各段行高（px）", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    heights.entries.sortedBy { it.key }.forEach { (k, v) ->
                        Text("$k… → ${v.joinToString(" / ")}", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "OpenMinis 数学渲染探路（P2-4 spike）", state = rememberWindowState(size = DpSize(1240.dp, 900.dp))) {
        var show by remember { mutableStateOf(false) }
        MaterialTheme { Surface { SpikeContent(show) { show = it } } }
    }
}

