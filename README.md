# Dynmap-KubeJS

> A fork of [Dynmap](https://github.com/webbukkit/dynmap) for **Minecraft 1.20.1 / Forge**. It fixes command registration so `/dynmap`, `/dmap`, `/dmarker` and `/dynmapexp` work with their original syntax. Prebuilt jar: [Releases](../../releases).

Dynmap 的 Forge 端在 `ServerAboutToStartEvent` 注册命令。`/reload` 会重建 `Commands` 并换掉 dispatcher，而上游只在启动时注册一次 —— 节点随旧 dispatcher 一起被丢弃，命令在玩家进服前就已失效。本分支改为在 `RegisterCommandsEvent` 注册，该事件对每次 `Commands` 重建都会触发。

## 用法

与原版 Dynmap 完全一致：

```
/dynmap radiusrender <world> <x> <z> <radius> [<map>]
/dynmap fullrender <world>[:<map>]
/dynmap cancelrender <world>
/dynmap stats
/dmap ...    /dmarker ...    /dynmapexp ...
```

服务器控制台不带前导 `/`。

## 构建

JDK 17：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat :forge-1.20:build --no-daemon
```

产物：`target/Dynmap-3.6-forge-1.20.jar`

## 部署

替换 `mods/` 里的 jar，配置沿用原版。`DynmapBlockScan` 无需改动。

## 兼容性

| | 版本 |
|---|---|
| Minecraft | 1.20 / 1.20.1 |
| Forge | 46+（实测 47.4.16） |
| Java | 17 |
| DynmapBlockScan | 3.6-251，无需改动 |

## 链接

- [Wiki](https://github.com/Childish-Ghost/dynmap-KubeJS/wiki)
- [上游 Dynmap](https://github.com/webbukkit/dynmap)

Apache Public License v2.
