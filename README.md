# Dynmap-KubeJS

> **TL;DR (English)** — A fork of [Dynmap](https://github.com/webbukkit/dynmap) 3.6 for **Minecraft 1.20.1 / Forge** that adds a render entry point **bypassing Minecraft's command dispatcher**, so map rendering still works on servers where Dynmap's `/dynmap` command fails to register. Prebuilt jar is on the [Releases](../../releases) page.
>
> 中文说明见下。

---

## 这是什么

这是 [Dynmap](https://github.com/webbukkit/dynmap) 的一个派生分支，基线是上游 **`v3.6`** 标签（commit `cee25bc518`）。

它在原版 Dynmap 之上只做了一件事：**给 Dynmap 内核加了一个不经过 Minecraft 命令系统的渲染入口**，用于绕开「`/dynmap` 命令注册成功但无法执行」的问题。

除补丁涉及的两个文件外，其余代码与上游 `v3.6` 完全一致。

---

## 背景：`/dynmap` 命令失效

### 现象

在 Minecraft **1.20.1 / Forge 47.4.16** 服务器上：

| 项目 | 状态 |
|---|---|
| Dynmap Web 地图 | ✅ 正常（`http://<服务器>:8123`） |
| 方块渲染 / 增量更新 | ✅ 正常 |
| 命令注册 | ✅ 日志打印 `[Dynmap] Register commands` |
| **`/dynmap` 手动触发渲染** | ❌ **无法执行** |
| `/spark`、原版命令 | ✅ 正常 |

即 Dynmap **功能是好的**，只是无法通过命令主动触发全图/半径渲染。

### 排查过程中的关键事实

- Forge 生命周期顺序为 `RegisterCommandsEvent` → `ServerAboutToStartEvent` → `ServerStartedEvent`，且 `allowLogins` 是在 `ServerStartedEvent` **之后**才置位的。
- 服务器曾因 DynmapBlockScan 扫描期间的 `Invalid modellist patch` 刷屏触发 `ServerHangWatchdog`（单 tick 超 60 秒）。已通过把 `server.properties` 的 `max-tick-time` 提到 `1800000` 解决，**与本次改动无关**，但排查时容易混淆。
- 社区有指向 KubeJS 的说法。但实际检查 KubeJS 2001 的 mixin 源码后可以确认：其 mixin 只注入 `MinecraftServer` 的 `<init>` / `tickServer` / `reloadResources`，以及给 `CommandSourceStack` 增加一个 `kjs$sendSuccess` 重载，**不触碰 `Commands` 与命令派发器**。

> ⚠️ **诚实说明**：`/dynmap` 失效的**根因至今未被证实**。KubeJS 是嫌疑人之一，但证据不足以下定论。
>
> 本分支**没有去修根因**，而是让渲染**绕开命令系统**——所以即使根因始终不明，渲染照样能跑。

---

## 补丁做了什么

改动共 **2 个文件、+70 行**，全部落在非核心类中。

### 1. `DynmapPlugin.apiRunCommand(String)`

```java
public boolean apiRunCommand(String cmdline)
```

等价于在服务器控制台敲 `/dynmap <cmdline>`，但**不经过命令派发器**，直接把命令字符串交给 Dynmap 内核自己的命令处理器 `DynmapCore.processCommand(...)`。

### 2. `DynmapPlugin.apiAutoRender()`

读取配置项，在服务器启动完成后自动触发一次半径渲染。由 `DynmapMod.onServerStarted()` 调用。

### 这两处是怎么协同的

```java
DynmapCommandSender dsender = new ForgeCommandSender() {   // 复用无参构造：内部 sender 保持 null
    @Override public void sendMessage(String msg) { Log.info("[api] " + msg); }   // 但 sendMessage 永不空指针
};
return core.processCommand(dsender, "dynmap", cmd, args);
```

`ForgeCommandSender` 的无参构造函数会把内部的 `sender` 留为 `null`，而它自己的 `sendMessage()` 是空安全的。Dynmap 的渲染路径（`MapManager.renderWorldRadius` / `renderFullWorld`）里有若干处会无条件调用 `sender.sendMessage(...)`——用一个非空的匿名子类顶上去，就永远不会空指针，**因此完全不需要改动 `MapManager`**。

### 启动时序（已验证）

```
DynmapMod.onServerStarted
  └─ plugin.serverStarted()   → onStart() → core.enableCore(null)
                                 → initConfiguration(null) → configuration = new ConfigurationNode(f)
  └─ plugin.apiAutoRender()   ← 此刻 core 与 configuration 必定已就绪
```

---

## 方案对比

| | 方案甲 | **方案乙2（本分支采用）** |
|---|---|---|
| 改动位置 | `MapManager` + 把 `renderWorldRadius` 改 `public` + 3 处判空 | `DynmapPlugin` 内新增 2 个方法 + `DynmapMod` 1 行 |
| 是否触碰核心类 | 是 | **否** |
| 需要 AccessTransformer | 否 | 否 |
| 需要 `createCommandSourceStack` | 否 | 否 |
| 需要 KubeJS 参与 | 否 | **否** |
| 与 DynmapBlockScan 兼容 | 签名变更，有风险 | **零风险（无签名变更）** |
| sender 为 null | 打补丁绕开 | 匿名子类顶替，永不空指针 |

放弃的其他路线：改用 BlueMap（需要 Java 21，且 `BluemapCreateEntityAddon` 要求 BlueMap ≥ 5.7）、KubeJS 运行时 mixin 注入（KubeJS 不支持）、KubeJS 脚本反射（`renderWorldRadius` 是包级私有）、编辑 `run.sh` / `-Xbootclasspath/a:` / `-Djava.class.path`（均无效）。

---

## 配置

在 `dynmap/configuration.txt` 中追加：

```yaml
autorender-radius: 1000
autorender-world: world     # 可选，默认 world
autorender-map: flat        # 可选，默认 flat
```

`autorender-radius` ≤ 0 或缺失时，自动渲染**不启用**（启动日志会说明）。

渲染中心坐标目前**固定为 `0 0`**。需要别的中心点，或想要 `autorender-x` / `autorender-z` 配置项，改 `apiAutoRender()` 即可。

`apiRunCommand` 是 `public` 方法，`DynmapMod.plugin` 是 `public static`，所以任何模组（包括 KubeJS）都可以反射调用：

```java
DynmapMod.plugin.apiRunCommand("radiusrender world 0 0 1000 flat");
```

但**默认路径完全不需要 KubeJS**——服务器启动时自动渲染。

---

## 构建

### 环境要求

| 项目 | 版本 |
|---|---|
| JDK | **17**（必须是 17，不能用 21） |
| Gradle | 7.4.2（wrapper 会自动下载） |
| ForgeGradle | 5.1.+（`forge-1.20/build.gradle` 中声明） |

### 命令

`cmd`：

```bat
set JAVA_HOME=C:\Program Files\Java\jdk-17
gradlew.bat :forge-1.20:build --no-daemon
```

PowerShell：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat :forge-1.20:build --no-daemon
```

产物：`target/Dynmap-3.6-forge-1.20.jar`

首次构建约 10 分钟（要下载 Gradle 发行版、ForgeGradle 与 MC 1.20 依赖）；之后有缓存约 1 分钟。

### ⚠️ 本分支对 `settings.gradle` 的改动

上游 `settings.gradle` 声明了 **40+ 个子项目**（含 17 个 Fabric 版本）。Gradle 默认会**无条件配置全部子项目**，导致每个 Fabric 模块都通过 Loom 去下载对应版本的 Minecraft，合计十几 GB——即使你只想构建 `:forge-1.20`。

而 `:forge-1.20:build` 实际只需要三个项目：

```
:forge-1.20 → :DynmapCore → :DynmapCoreAPI
```

因此本分支把 `settings.gradle` 收窄为这三个项目。**副作用**：本分支无法再构建 Bukkit / Spigot / Fabric 目标。

需要恢复完整平台支持：

```bash
git show v3.6:settings.gradle > settings.gradle
```

（代价是重新面对上面那个多版本下载问题。）

---

## 部署

```bash
# 1) 备份原版 jar
sudo docker exec MCSM-b2628e cp \
  "/data/mods/[Olimap]Dynmap-3.6-forge-1.20.jar" \
  "/data/mods/[Olimap]Dynmap-3.6-forge-1.20.jar.bak"

# 2) 新 jar 先传到宿主机（如 /tmp/），再放进容器（文件名含方括号，务必加引号）
sudo docker cp /tmp/Dynmap-3.6-forge-1.20.jar \
  'MCSM-b2628e:/data/mods/[Olimap]Dynmap-3.6-forge-1.20.jar'

# 3) 追加配置
sudo docker exec MCSM-b2628e sh -c 'cat >> /data/dynmap/configuration.txt <<EOF

# Dynmap-KubeJS API patch
autorender-radius: 1000
autorender-world: world
autorender-map: flat
EOF'
```

`DynmapBlockScan` **不需要替换**——补丁没有改动任何方法签名。

---

## 验证

重启服务器后：

```bash
sudo docker exec MCSM-b2628e grep -E 'apiRunCommand|apiAutoRender|\[api\]' /data/logs/latest.log | tail -30
```

预期输出：

```
[Dynmap] apiRunCommand: /dynmap radiusrender world 0 0 1000 flat
[Dynmap] [api] ...（渲染进度信息）
```

### 产物自检

对已构建的 jar 可以直接反汇编确认补丁在里面：

```bash
javap -p -classpath target/Dynmap-3.6-forge-1.20.jar org.dynmap.forge_1_20.DynmapPlugin | findstr api
javap -c -p -classpath target/Dynmap-3.6-forge-1.20.jar org.dynmap.forge_1_20.DynmapMod | findstr apiAutoRender
```

应分别看到：

```
public boolean apiRunCommand(java.lang.String);
public void apiAutoRender();
```

```
invokevirtual #242   // Method org/dynmap/forge_1_20/DynmapPlugin.apiAutoRender:()V
```

---

## 已知限制

1. **`/dynmap` 命令依然是坏的。** 本分支绕开了它，没有修它。控制台/游戏内仍然不能用 `/dynmap`。
2. **根因未证实。** 不能据此断定是 KubeJS 的问题。
3. **渲染中心固定 `0 0`。** 见上文。
4. **升级需重新打补丁。** 跟随上游新版本时需要重新应用这两处改动。
5. **每次构建都是 `3.6-Dev` 版本号**，除非设置 `BUILD_NUMBER` 环境变量。
6. 构建目标为 Forge `1.20-46.0.1`（与上游官方 `Dynmap-3.6-forge-1.20.jar` 相同的目标），运行在 Forge 47.4.16 / MC 1.20.1 上。

---

## 兼容性

| | 版本 |
|---|---|
| Minecraft | 1.20 / 1.20.1（`mods.toml` 声明 `[1.20,1.21)`） |
| Forge | 46+（声明 `[46,)`，实测 47.4.16） |
| Java | 17 |
| DynmapBlockScan | 3.6-251（二进制兼容，无需改动） |

---

## 上游

本分支基于 [webbukkit/dynmap](https://github.com/webbukkit/dynmap) `v3.6`。

上游原始 README 保留在 [`README.upstream.md`](README.upstream.md)，包含完整的平台支持列表、数据存储说明与构建指南。

Dynmap 采用 Apache Public License v2，本分支沿用同一许可。
