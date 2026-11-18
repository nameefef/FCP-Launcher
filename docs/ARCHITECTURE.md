# FCP 架构草案

## 原则

1. **单一主许可证**：整仓 GPL-3.0。
2. **PCL 不进仓**：不 fork、不 vendor PCL 源码；命名自由使用 FCP。
3. **两端共用启动逻辑**：版本解析、下载、认证尽量抽到共享模块；UI 分平台。
4. **联机走开源组件**：优先 FCL 生态已用的 EasyTier / Terracotta 等，注意 AGPL/LGPL 传染面。

## 平台

| 平台 | 路线 |
|------|------|
| Android | 以 FCL 为起点 fork / 模块化改造，恢复下载 + 联机增强 |
| Desktop | Kotlin + Compose Multiplatform Desktop，复用 `:core`。Win/macOS/Linux 用系统 Java 启动独立进程，原生库来自版本 JSON 的 LWJGL 分类器 |
| Web | 静态/Worker 官网：介绍、各端安装包、协议与致谢 |

## v0.1 与暂缓

Android 先做账号、下载和游戏启动。桌面端和联机仍不在这条线上。启动会检查已安装的版本和当前账号；Pojav / FCL 的 JRE 与渲染库还没有打进安装包，缺了会直接说明。

| 已包含 | 暂缓 |
|--------|------|
| 离线账号（`OfflinePlayer:` UUID，与 HMCL / FCL 相同） | 官方 Microsoft 登录（不申请 Client ID，不借用其他启动器的 Azure 应用） |
| LittleSkin，以及高级选项里的自定义 Yggdrasil / authlib-injector 地址 | 把 Pojav / FCL 的 JRE 和渲染 `.so` 打进 Android 安装包（[#11](https://github.com/nameefef/FCP-Launcher/issues/11) 的后续） |
| BMCLAPI2 优先、Mojang 官方回退的版本清单和客户端 / 依赖库 / 资源下载 | 联机（[#4](https://github.com/nameefef/FCP-Launcher/issues/4)） |
| 启动参数与进程：Android 在进程内调用 `JLI_Launch`；桌面端用系统 Java 启动独立进程。缺 Java、原生库或资源时写明原因并记日志 | — |
| 桌面端（[#5](https://github.com/nameefef/FCP-Launcher/issues/5)）：Compose Desktop，游戏目录、内存、Java 路径、下载源 | — |
| 关于页保留 BMCLAPI 署名；`PRODUCT_FULL_NAME` 只在连点彩蛋中出现 | — |

下载源只保留口碑稳定、HMCL / FCL 也在用的两组：

1. **BMCLAPI2** `https://bmclapi2.bangbang93.com`（bangbang93）。国内常用，HMCL / FCL 的镜像档走这里。版本清单、客户端、`libraries.minecraft.net`、资源对象都会先改写到这个域名。
2. **Mojang 官方**：`piston-meta.mojang.com`、`piston-data.mojang.com`、`launchermeta.mojang.com`、`launcher.mojang.com`、`libraries.minecraft.net`、`resources.download.minecraft.net`。镜像失败时使用原始地址。

不接入 MCIM、临时网盘或其他不知名镜像。Maven Central 上的非 Mojang 构件保持官方地址，v0.1 不额外包一层镜像。

## 首期里程碑

- M0：本仓库骨架 + NOTICE + 空桌面/安卓/网页占位
- M1：官网可 HTTPS 访问，提供占位下载页
- M2：Android 账号 + BMCLAPI 下载 + 游戏启动路径（见 `android/`；JRE / 渲染库仍不在安装包内）
- M3：Desktop 三端可装包 + 基础启动/下载冒烟
- M4：联机 MVP + 回归清单
