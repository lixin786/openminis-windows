# Provenance / 来源声明

This file states where the code in this directory comes from and what the GNU GPL v3 requires of anyone who
distributes it. It sits next to [`LICENSE`](LICENSE) (the full GPL v3 text) and complements
[`PORTING.md`](PORTING.md) (file-by-file porting notes).

本文件说明本目录代码的来源，以及分发者依 GPLv3 须履行的义务；与 `LICENSE`（GPLv3 全文）并列，`PORTING.md` 为逐文件移植说明。

## Upstream / 上游

| Field | Value |
|---|---|
| Project | OpenMinis (iOS Swift + Android Kotlin AI agent app) |
| Repository | https://github.com/OpenMinis/OpenMinis |
| Locked upstream commit | `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` (short `b4c0661`, "Merge pull request #408 from OpenMinis/v1.14", 2026-09-29) |
| Upstream release | v1.14 |
| Upstream license | GNU General Public License v3.0 |
| Upstream path used | `src/android/app` (Kotlin sources and JVM unit tests); upstream `strings.xml` resources are not yet used |

Every file under `app/src/main/kotlin/com/openminis/app/**` and `app/src/test/kotlin/com/openminis/app/**` is copied from
that commit, either verbatim or with the minimal edits listed in `PORTING.md` ("Minimal edits"; desktop edits are marked
`[desktop]` in the code). Files under `com/openminis/desktop/**` and the `android/**` JVM shims are new code written for
this port; each carries a header saying it is part of OpenMinis and licensed under the GPL v3. Upstream copyright
notices are kept unchanged.

`app/src/main/kotlin/com/openminis/app/**` 与对应测试复制自上述锁定 commit（原样或按 `PORTING.md` 所列最小修改，代码中以 `[desktop]` 标注）；
`com/openminis/desktop/**` 与 `android/**` 垫片为本移植新写，文件头均声明属于 OpenMinis、以 GPLv3 授权。上游版权声明保持不变。

## This port / 本移植

| Field | Value |
|---|---|
| Repository | https://github.com/lixin786/openminis-windows (`src/desktop`) |
| Base of the port | batch1 + hotfix1 (repository commit `4877f80`), then hotfix2/3, batch2, batch3, security batch |
| License | GNU General Public License v3.0 (same as upstream, no additional permissions or restrictions) |

## Third-party components / 第三方组件

Distributed binaries (jar / MSI / EXE) also contain these libraries, each under its own license (all GPL-v3-compatible):

| Component | License |
|---|---|
| Kotlin stdlib, kotlinx-coroutines, kotlinx-serialization | Apache-2.0 |
| Compose Multiplatform for Desktop, Material 3 | Apache-2.0 |
| OkHttp 4 / okhttp-sse (+ Okio) | Apache-2.0 |
| org.json | Public domain ("JSON License" removed since 20231013) |
| com.mikepenz multiplatform-markdown-renderer | Apache-2.0 |
| com.huaban jieba-analysis 1.0.2 (+ commons-lang3) | Apache-2.0 |
| JLaTeXMath 1.0.7 (org.scilab.forge; math in chat) | GPL-2.0-or-later with classpath exception (used under GPL-3.0); bundled fonts: Knuth / BaKoMa, SIL OFL-1.1, dsrom — licence texts inside the jar under `org/scilab/forge/jlatexmath/fonts/licences/` |
| JNA / jna-platform 5.15.0 (Windows DPAPI for API keys) | Apache-2.0 / LGPL-2.1 dual license, used under Apache-2.0 |
| Lucide icons (lucide-static 1.55.0; 57 icons generated into `ui/icons/LucideIcons.kt` by `tools/gen-lucide-icons.py`) | ISC — `third_party/lucide/LICENSE` |
| Inter 4.1 (fonts/Inter-*.ttf) | SIL OFL-1.1 — `third_party/inter/OFL.txt` |
| JetBrains Mono 2.304 (fonts/JetBrainsMono-*.ttf) | SIL OFL-1.1 — `third_party/jetbrains-mono/OFL.txt` |
| JetBrains Runtime / Temurin JDK (when bundled by `packageMsi` / `packageExe`) | GPL-2.0 with Classpath Exception |

UI-REFRESH license red lines: no code was copied from Cherry Studio (AGPL-3.0), LobeChat or Open WebUI (custom
licenses); their designs were used as interaction references only. The OFL fonts are bundled unmodified and are not
sold separately.

## Obligations when you distribute / 分发时的义务（GPLv3）

1. **Source**: whoever conveys a jar, MSI or EXE must offer the complete corresponding source (this repository at the
   exact commit the binary was built from, including the build scripts), under the GPL v3 — e.g. a link to the tagged
   commit next to the download (GPLv3 §6).
2. **License text**: ship `LICENSE` (GPL v3) with every copy; keep this file and the per-file headers (§4, §5).
3. **Notices**: keep upstream and port copyright notices; modified files must say they were modified and roughly when
   (§5a — the file headers and `PORTING.md` serve this).
4. **Same license**: derivative works must be licensed as a whole under the GPL v3; no extra restrictions (no
   additional terms forbidding redistribution, no DRM-style lockdown, §10, §12).
5. **No warranty**: the software is provided without warranty (§15, §16). It runs commands on the host without a
   sandbox; see "Safety model" in `PORTING.md`.
6. **Trademarks**: the GPL grants no trademark rights; "OpenMinis" names and logos belong to their owners.

1. 分发 jar / MSI / EXE 必须同时提供构建所用 commit 的完整对应源码（含构建脚本），以 GPLv3 授权；
2. 每份副本附带 `LICENSE`，保留本文件与文件头；
3. 保留上游与本移植的版权声明，修改过的文件须注明已修改；
4. 衍生作品整体以 GPLv3 授权，不得附加限制；
5. 无担保——本程序在宿主机上直接执行命令、无沙箱；
6. GPL 不授予商标权。
