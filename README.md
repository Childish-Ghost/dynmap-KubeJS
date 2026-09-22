# Dynmap-KubeJS

> **TL;DR (English)** — A fork of [Dynmap](https://github.com/webbukkit/dynmap) for **Minecraft 1.20.1 / Forge**.
>
> Dynmap's Forge port registered its commands at `ServerAboutToStartEvent`, which is too late for Forge 1.19+ — the nodes never made it into the command tree the game resolves against. This fork registers them at `RegisterCommandsEvent` instead, restoring `/dynmap`, `/dmap`, `/dmarker` and `/dynmapexp` with their original syntax. A programmatic render API is also included as a fallback that does not depend on the command system at all.
>
> Prebuilt jar: [Releases](../../releases). 中文说明见下。

---

## 这是什么

[Dynmap](https://github.com/webbukkit/dynmap) 的派生分支，基线是上游 **`v3.6`** 标签（commit `cee25bc518`）。

在原版 Dynmap 之上做了两件事：

1. **修复命令注册时机** —— 让 `/dynmap`、`/dmap`、`/dmarker`、`/dynmapexp` 四个命令**按原版语法真正可用**。（主要改动）
2. **新增渲染 API** —— `apiRunCommand` / `apiAutoRender`，完全不依赖命令系统，作为保底手段。

只保留 **MC 1.20 / 1.20.1 的 Forge 平台**，其余 35 个平台模块已从仓库删除。

---

## 根因：命令注册得太晚了

### 现象

服务器：Minecraft **1.20.1** / Forge **47.4.16** / Java 17。

| 项目 | 状态 |
|---|---|
| Dynmap Web 地图 | ✅ 正常 |
| 方块渲染、增量更新 | ✅ 正常 |
| 命令注册日志 | ✅ 打印 `Register commands` |
| **`/dynmap` 等四个命令** | ❌ **无法执行** |
| `/spark`、原版命令 | ✅ 正常 |

最迷惑的地方是：**日志明明说注册成功了，命令却用不了。**

### 原因

Forge 1.19+ 的 `Commands` 在构造时会触发 `RegisterCommandsEvent`，**这才是游戏真正解析命令所用的钩子**。事件顺序是：

```
RegisterCommandsEvent            ← 正确的注册点
    ↓
ServerAboutToStartEvent          ← Dynmap 在这里注册（太晚了）
    ↓
ServerStartedEvent
```

Dynmap 的 Forge 端写的是 **1.12 时代的老写法** —— 在 `ServerAboutToStartEvent` 里直接往 `server.getCommands().getDispatcher()` 塞节点：

```java
// 原版 Dynmap —— 时机错误
@SubscribeEvent
public void onServerStarting(ServerAboutToStartEvent event) {
    server = event.getServer();
    if(plugin == null) plugin = proxy.startServer(server);
    plugin.onStarting(server.getCommands().getDispatcher());   // ← 太晚
}
```

整个仓库 grep `RegisterCommandsEvent` **零命中** —— 这个事件从未被使用过。

节点确实被加进了 dispatcher，所以日志正常；但它们没有成为游戏解析命令那棵树的一部分，因此命令无法执行。

---

## 修复

### 1. 在正确的事件里注册（`DynmapMod`）

```java
@SubscribeEvent
public void onRegisterCommands(RegisterCommandsEvent event) {
    CommandDispatcher<CommandSourceStack> cd = event.getDispatcher();
    new DynmapCommand(null).register(cd);
    new DmapCommand(null).register(cd);
    new DmarkerCommand(null).register(cd);
    new DynmapExpCommand(null).register(cd);
    commandsRegistered = true;
    Log.info("Register commands (RegisterCommandsEvent): ...");
}
```

### 2. 命令处理器延迟解析 plugin（`DynmapCommandHandler`）

`RegisterCommandsEvent` 比 `ServerAboutToStartEvent` **更早**触发，那时 `DynmapPlugin` 还不存在（它在 `ServerAboutToStartEvent` 才创建，创建时还会顺带跑 `onEnable()` 建 core、读配置、广播 API）。

所以**不能**为了注册命令而提前创建 plugin —— 那会打乱初始化时序。做法是让命令处理器**延迟解析**目标：

```java
private DynmapPlugin plugin() {
    return (plugin != null) ? plugin : DynmapMod.plugin;   // 执行时才解析
}
```

节点提前注册，实际执行时再去拿已经就绪的 plugin。

### 3. 保留一条受保护的兜底路径（`DynmapPlugin.onStarting`）

```java
if (DynmapMod.commandsRegistered) {
    Log.info("Commands already registered at RegisterCommandsEvent");
    return;                       // 同一个 literal 注册两次会让 Brigadier 抛异常
}
```

两条路径**只会走一条** —— Brigadier 的 `CommandDispatcher.register()` 在遇到已存在的 literal 时会尝试合并子节点，而子节点是参数节点（非 literal），会直接抛出 `IllegalStateException`。

### 4. 诊断日志

`onServerStarting` 现在会打印实际 dispatcher 里是否还有那四个命令：

```
[Dynmap] live dispatcher: dynmap=true dmap=true dmarker=true dynmapexp=true rootchildren=NN
```

如果这里显示 `dynmap=false`，说明游戏执行的 dispatcher 与注册时用的**不是同一个实例** —— 那才是真正的根因。这条日志就是为了在服务器上一次性判定。

> ⚠️ **诚实说明**：这个修复在**字节码层面已完整验证**（见下），但**尚未在你的服务器上实测**。四年未动的注册时机是一个真实的 bug，修复它有充分依据；但如果你期望的是"绝对确定"，那需要你启动一次服务器来确认。请把上面那行诊断日志发回来。

---

## 渲染 API（保底手段）

即使命令修复不生效，这两个方法也能让渲染跑起来 —— 它们完全不经过 Minecraft 的命令系统。

| 方法 | 签名 | 用途 |
|---|---|---|
| `apiRunCommand` | `public boolean apiRunCommand(String cmdline)` | 执行任意 `/dynmap` 子命令（不含前导 `/` 与 `dynmap`） |
| `apiAutoRender` | `public void apiAutoRender()` | 按配置在启动时自动渲染一次 |

实现要点：`ForgeCommandSender` 的无参构造会把内部 `sender` 留为 `null`，而它自己的 `sendMessage()` 是空安全的。用一个匿名子类顶上去，Dynmap 渲染路径里那些无条件调用 `sender.sendMessage(...)` 的地方就永远不会空指针 —— **因此完全不需要改动 `MapManager`**。

`DynmapMod.plugin` 是 `public static`，任何模组（含 KubeJS）都能反射调用：

```java
DynmapMod.plugin.apiRunCommand("radiusrender world 0 0 1000 flat");
```

配置（`dynmap/configuration.txt`，不写则自动渲染不启用）：

```yaml
autorender-radius: 1000
autorender-world: world     # 可选，默认 world
autorender-map: flat        # 可选，默认 flat
```

渲染中心目前固定为 `0 0`；走 `/dynmap radiusrender <world> <x> <z> <radius> <map>` 则坐标由你指定。

---

## 与原版用法一致

命令可用之后，用法与官方 Dynmap 文档**完全相同**：

```
/dynmap radiusrender <world> <x> <z> <radius> [<map>]
/dynmap fullrender <world>[:<map>]
/dynmap cancelrender <world>
/dynmap stats
/dmap ...
/dmarker ...
/dynmapexp ...
```

服务器控制台里**不要带前导 `/`**（这是 Minecraft 本身的规则，与原版一致）。

---

## 构建

| 项目 | 版本 |
|---|---|
| JDK | **17**（必须，不能用 21） |
| Gradle | 7.4.2（wrapper 自动下载） |
| ForgeGradle | 5.1.+ |

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat :forge-1.20:build --no-daemon
```

产物：`target/Dynmap-3.6-forge-1.20.jar`

首次构建约 10 分钟（需下载 Gradle 发行版 + ForgeGradle + MC 1.20 依赖），之后有缓存约 1 分钟。

### 本分支只保留 `forge-1.20`

上游包含 **40+ 个平台模块**（spigot、15 个 `bukkit-helper-*`、10 个 Fabric 版本、9 个 Forge 版本）。

它们已从本仓库**删除**，原因有二：

1. **Gradle 会无条件配置全部子项目** —— 即使只构建 `:forge-1.20`，每个 Fabric 模块也会通过 Loom 去下载对应版本的 Minecraft，合计十几 GB。
2. 本分支只为 MC 1.20 / 1.20.1 Forge 服务。

`:forge-1.20:build` 只需要三个项目：

```
:forge-1.20 → :DynmapCore → :DynmapCoreAPI
```

取回完整多平台源码：

```bash
git remote add upstream https://github.com/webbukkit/dynmap.git
git fetch upstream --tags
git checkout v3.6
```

---

## 部署

```bash
# 1) 备份原版 jar
sudo docker exec MCSM-b2628e cp \
  "/data/mods/[Olimap]Dynmap-3.6-forge-1.20.jar" \
  "/data/mods/[Olimap]Dynmap-3.6-forge-1.20.jar.bak"

# 2) 新 jar 先传到宿主机（如 /tmp/），再放进容器
#    文件名含方括号，整段务必加引号
sudo docker cp /tmp/Dynmap-3.6-forge-1.20.jar \
  'MCSM-b2628e:/data/mods/[Olimap]Dynmap-3.6-forge-1.20.jar'
```

`DynmapBlockScan` **不需要替换** —— 补丁没有改动任何方法签名。

---

## 验证

启动服务器后看这两行：

```bash
sudo docker exec MCSM-b2628e grep -E 'Register commands|live dispatcher' /data/logs/latest.log
```

期望：

```
[Dynmap] Register commands (RegisterCommandsEvent): dynmap=true dmap=true dmarker=true dynmapexp=true rootchildren=NN
[Dynmap] live dispatcher: dynmap=true dmap=true dmarker=true dynmapexp=true rootchildren=NN
```

然后游戏内或控制台试 `/dynmap stats`（控制台不带 `/`）。

### 产物自证（不启动服务器也能验）

```bash
javap -p -classpath target/Dynmap-3.6-forge-1.20.jar org.dynmap.forge_1_20.DynmapMod | findstr RegisterCommands
javap -p -classpath target/Dynmap-3.6-forge-1.20.jar org.dynmap.forge_1_20.DynmapCommandHandler
```

---

## 已知限制

1. **命令修复未在真实服务器实测**（字节码已验证）。诊断日志会给出判定。
2. **渲染中心固定 `0 0`**（仅指 `apiAutoRender` 的自动渲染；用 `/dynmap radiusrender` 时坐标由你指定）。
3. **跟随上游升级需重新打补丁。**
4. 构建版本号默认是 `3.6-Dev`，除非设置 `BUILD_NUMBER` 环境变量。
5. 构建目标为 Forge `1.20-46.0.1`（与上游官方 jar 相同），运行在 Forge 47.4.16 / MC 1.20.1 上。

---

## 兼容性

| | 版本 |
|---|---|
| Minecraft | 1.20 / 1.20.1（声明 `[1.20,1.21)`） |
| Forge | 46+（实测 47.4.16） |
| Java | 17 |
| DynmapBlockScan | 3.6-251，二进制兼容，无需改动 |

---

## 上游

基于 [webbukkit/dynmap](https://github.com/webbukkit/dynmap) `v3.6`。上游原始 README 保留在 [`README.upstream.md`](README.upstream.md)。Apache Public License v2。
