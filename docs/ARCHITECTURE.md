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
| Desktop | 以 HMCL（Java / JavaFX）为底做 Win/macOS/Linux；不走 PCL 的 WPF/VB.NET |
| Web | 静态/Worker 官网：介绍、各端安装包、协议与致谢 |

## v0.1 与暂缓

v0.1 只做 Android 账号和下载，方便先合并。桌面端和联机不在这个 PR 里。

| 已包含 | 暂缓 |
|--------|------|
| 离线账号（`OfflinePlayer:` UUID，与 HMCL / FCL 相同） | 官方 Microsoft 登录（不申请 Client ID，不借用其他启动器的 Azure 应用） |
| LittleSkin，以及高级选项里的自定义 Yggdrasil / authlib-injector 地址 | 启动游戏进程（FCL 的 Amethyst / Pojav 原生层） |
| BMCLAPI2 优先、Mojang 官方回退的版本清单和客户端 / 依赖库 / 资源下载 | 桌面端（[#5](https://github.com/nameefef/FCP-Launcher/issues/5)） |
| 关于页保留 BMCLAPI 署名；`PRODUCT_FULL_NAME` 只在连点彩蛋中出现 | 联机（[#4](https://github.com/nameefef/FCP-Launcher/issues/4)） |

下载源只保留口碑稳定、HMCL / FCL 也在用的两组：

1. **BMCLAPI2** `https://bmclapi2.bangbang93.com`（bangbang93）。国内常用，HMCL / FCL 的镜像档走这里。版本清单、客户端、`libraries.minecraft.net`、资源对象都会先改写到这个域名。
2. **Mojang 官方**：`piston-meta.mojang.com`、`piston-data.mojang.com`、`launchermeta.mojang.com`、`launcher.mojang.com`、`libraries.minecraft.net`、`resources.download.minecraft.net`。镜像失败时使用原始地址。

不接入 MCIM、临时网盘或其他不知名镜像。Maven Central 上的非 Mojang 构件保持官方地址，v0.1 不额外包一层镜像。

## 首期里程碑

- M0：本仓库骨架 + NOTICE + 空桌面/安卓/网页占位
- M1：官网可 HTTPS 访问，提供占位下载页
- M2：Android v0.1 账号 + BMCLAPI 下载（进行中，见 `android/`）
- M3：Desktop 三端可装包 + 基础启动/下载冒烟
- M4：联机 MVP + 回归清单
