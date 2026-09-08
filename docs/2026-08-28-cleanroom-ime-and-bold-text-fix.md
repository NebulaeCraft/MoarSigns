# 迁移到 CleanroomMC 后的告示牌输入法与加粗掉字修复

- 日期：2026-08-28
- 版本：`6.0.0.10` → `6.0.0.12`
- 环境：Minecraft 1.12.2，客户端由 **Forge + Java 8** 迁移到 **CleanroomMC + Java 25**

迁移后出现两个问题，根因都在 Cleanroom 对原版类打的补丁上，本模组的代码恰好踩中了这两处行为变化。

---

## 问题 1：编辑告示牌时只有第 4 行能调出中文输入法

### 根因

Cleanroom 在 `patches/minecraft/net/minecraft/client/gui/GuiTextField.java.patch` 里给 `setFocused()` 加了一行：

```java
this.isFocused = isFocusedIn;
IMEHandler.setIME(this.isFocused);   // ← Cleanroom 新增
```

`com.cleanroommc.client.IMEHandler` 是一个**全局**开关，不区分是哪个输入框
（Windows 实现直接调 `ImmAssociateContext(hwnd, null)` 摘掉整个窗口的输入法上下文）。
也就是说：**最后一次调用 `setFocused()` 的输入框说了算。**

而 `GuiMoarSign.mouseClicked()` 原本是无脑遍历四个输入框：

```java
for (GuiTextField guiTextField : guiTextFields)
    guiTextField.mouseClicked(x, y, b);   // 内部每次都会调 setFocused()
```

按索引 `0 → 3` 遍历，**最后一次调用永远来自第 4 行**：

| 点击的行 | 最后一次 `setFocused` | IME 状态 |
| --- | --- | --- |
| 第 4 行 | 第 4 行 `setFocused(true)` | 开 ✅ |
| 第 1/2/3 行 | 第 4 行 `setFocused(false)` | 关 ❌ |

> 旁证：旧代码里**上下方向键**切换行的顺序是对的（先 `false` 后 `true`），
> 所以在修复前，用方向键切到第 1 行是可以打中文的，只有鼠标点击不行。

### 修复

新增 `GuiMoarSign.setSelectedTextField(int)`，保证被选中的行**最后**调用 `setFocused(true)`：

```java
public void setSelectedTextField(int index) {
    selectedTextField = index;

    for (int i = 0; i < guiTextFields.length; i++) {
        if (guiTextFields[i] != null && i != index)
            guiTextFields[i].setFocused(false);
    }

    if (index != -1 && guiTextFields[index] != null)
        guiTextFields[index].setFocused(true);
}
```

所有切换焦点的路径统一走它：`initGui()`、`mouseClicked()`、方向键/回车切行。
另外 `onGuiClosed()` 调用 `setSelectedTextField(-1)`，关闭界面时把输入法上下文还回去，
避免退出告示牌后游戏里仍然挂着 IME。

---

## 问题 2：含加粗文字的告示牌尾部掉字

这不是一个 bug，是三个缺陷叠加。

### a. 显示少一个字（迁移当天就出现的现象）

Cleanroom 重写了 `GuiUtilRenderComponents.splitText()` 的断行逻辑：

```java
-  int l = s2.lastIndexOf(32);                                  // 原版：找最后一个空格
+  com.cleanroommc.client.ICU4JInstances.BREAK_ITERATOR.setText(s4);
+  int l = com.cleanroommc.client.ICU4JInstances.BREAK_ITERATOR.preceding(s2.length());
```

中文没有空格，**原版**找不到断点（`lastIndexOf == -1`）就整段保留；
**ICU BreakIterator** 认为每两个汉字之间都可以断行，于是又往回缩了一个字。

`MoarSignRenderer` 恰好调了 `splitText(component, 90, ...)`，而紧接着的下一行本来就已经
按该行真实宽度裁剪过一次 —— 这次 `splitText` 属于多余且有害。
只有加粗行会超过 90px（见 b），所以只有加粗告示牌受影响。

### b. 真正把字写没了的：编辑与保存用了两套宽度口径

- **编辑框**（`GuiSignTextField.writeText`）计算剩余空间时，**没把插入字符的加粗像素算进去**，
  因此每行总能多塞进 1 个字符；
- **保存**（`GuiMoarSign.onGuiClosed`）却用 `§` 形式重新量宽（加粗每字 +1px）再 `trimStringToWidth` 裁剪，
  于是刚才多出来的那个字被裁掉并**写回存档**。

### c. `Utils.isUnderlined` 把任何 `§` 都当成下划线

```java
Pattern.compile("(\\{" + (char) 8747 + "n\\})|(" + (char) 167 + ")")
                                                   ^^^^^^^^^^^^^^^ 裸的 §
```

任何带格式的行都会被判为「有下划线」，白白预留 `getCharWidth('i')` 的宽度，渲染时再少一个字。

### 修复

| 位置 | 改动 |
| --- | --- |
| `GuiSignTextField.getRenderedWidth()` | 新增。先把 `{∫x}` 标记转成 `§x` 再量宽，与渲染器口径完全一致 |
| `GuiSignTextField.writeText()` | 改为逐字符/逐格式标记，每次都按**整行渲染宽度**校验；格式标记零宽，永远允许 |
| `GuiSignTextField.setText()` | 不再裁剪。载入既有文本时逐字节保留，画不下的部分只是显示不出来 |
| `GuiMoarSign.onGuiClosed()` | **删除全部裁剪逻辑**。输入端已经守住了上限，出口不再动存档 |
| `GuiMoarSign.update()` | 把每行可编辑宽度与该行字号、阴影的实际预算同步（原来固定 90，字号调大后保存会被砍） |
| `MoarSignRenderer` | 不再走 `splitText()`，直接取 `getFormattedText()` 后按行宽裁剪 |
| `Utils.isUnderlined()` | 正则收紧为只匹配 `{∫n}` / `§n` |

---

## 验证方式

Gradle 环境跑不起完整客户端，因此用**真实字体资产**离线复刻了原版度量后做仿真：

- 从 `minecraft-1.12.2.jar` 取出 `assets/minecraft/textures/font/ascii.png`
  和 `assets/minecraft/font/glyph_sizes.bin`；
- 按 `FontRenderer.readFontTexture()` / `getCharWidth()` / `getStringWidth()` /
  `trimStringToWidth()` 的原始算法逐行复刻（校验结果：`'i'=2`、`'a'=6`、`'{'=5`、汉字 `=9`，与原版一致）；
- 核对 Cleanroom 的 `FontRenderer.java.patch`，确认 `getStringWidth` /
  `trimStringToWidth` **未被修改**，仿真结果对新环境同样成立。

仿真结论（`§l` + 10 个汉字，行宽 90px）：

| | 存档中的文本 | 实际显示 |
| --- | --- | --- |
| Forge / Java 8 | 保持 10 字 | 9 字 |
| Cleanroom（修复前） | 首次保存被裁成 9 字 | 8 字 |
| Cleanroom（修复后） | **10 字，反复保存不变** | 9 字（与迁移前一致） |

编译验证：先用 `javac`（JDK 8）对着项目自己的 deobf Forge classpath 编译通过；
随后 `./gradlew build` 成功产出 jar，并反查 reobf 结果确认调用的是
`GuiSignTextField.func_146195_b(Z)V` —— 即 Cleanroom 挂 IME 钩子的那个 `setFocused`。

### 一处如实说明

用户报告的是「每次保存再少一个字，直到只剩一个字」。
在原版字体度量下，仿真只能复现**一次**截断然后收敛稳定，复现不出无限递减。
因此没有去猜那个递减机制，而是直接根治：**保存路径现在完全不裁剪**，
存档文本在开关界面时逐字节不变。任何度量差异都不会再造成数据丢失。

**已经丢失的字符无法恢复**，那些告示牌需要手动补齐。

---

## 构建依赖修复

`./gradlew build` 卡在一个与本次改动无关的死链上：

```
Could not resolve mcp.mobius.waila:Hwyla:1.8.26-B41_1.12.2
  > Could not parse POM http://tehnut.info/maven/... > Already seen doctype.
```

`tehnut.info` 整个域名（含 `maven.` 子域）现在返回一个无关站点的页面，
而且是 **HTTP 200**，所以简单的 curl 探测会误判成「可用」，Gradle 缓存里的 `.pom` 也被这份 HTML 污染了。

Hwyla **不能直接删除** —— `integration/waila/Provider.java` 实现了 `mcp.mobius.waila.api.IWailaDataProvider`，
是真实的编译期依赖，删掉等于砍掉 WAILA 支持。

依次排查 ModMaven / BlameJared / GTNH / covers1624 / Forge maven 均为 404，
最终改用 **CurseMaven** 取同一构件（该构件此前已在本机 Gradle 缓存中完成 deobf，说明这条路已被验证过）：

```groovy
maven {
    name = "CurseMaven"
    url = "https://cursemaven.com"
}

deobfCompile "curse.maven:hwyla-253449:${config.hwyla_file}"   // 2568751 = Hwyla 1.8.26-B41_1.12.2
```

`build.properties` 中 `hwyla_version` 相应换成 `hwyla_file=2568751`。

---

## 改动文件

```
src/main/java/gory_moon/moarsigns/client/interfaces/sign/GuiMoarSign.java
src/main/java/gory_moon/moarsigns/client/interfaces/sign/GuiSignTextField.java
src/main/java/gory_moon/moarsigns/client/renderers/MoarSignRenderer.java
src/main/java/gory_moon/moarsigns/util/Utils.java
build.gradle
build.properties
```

## 安装

`local_build` 由 10 提至 12，产物为 `MoarSigns-1.12.2-6.0.0.12.jar`
（此前安装的是 `6.0.0.11`，不提版本号新包看起来反而更旧）。

**替换时必须删除旧 jar**，两个 MoarSigns 同时存在于 `mods/` 会导致 FML 报重复 mod 并崩溃。

## 参考

- [CleanroomMC/Cleanroom](https://github.com/CleanroomMC/Cleanroom)
  - `patches/minecraft/net/minecraft/client/gui/GuiTextField.java.patch`
  - `patches/minecraft/net/minecraft/client/gui/GuiUtilRenderComponents.java.patch`
  - `patches/minecraft/net/minecraft/client/gui/FontRenderer.java.patch`
  - `src/main/java/com/cleanroommc/client/IMEHandler.java`
