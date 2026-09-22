# Dynmap-KubeJS

> A fork of [Dynmap](https://github.com/webbukkit/dynmap) for **Minecraft 1.20.1 / Forge**. It fixes command registration so `/dynmap`, `/dmap`, `/dmarker` and `/dynmapexp` work with their original syntax. Prebuilt jar: [Releases](../../releases).

Dynmap 的 Forge 端在 `ServerAboutToStartEvent` 注册命令，对 Forge 1.19+ 来说太晚 —— 日志显示注册成功，命令却无法执行。本分支改为在 `RegisterCommandsEvent` 注册。

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
