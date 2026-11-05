# FCP Launcher

跨平台 Minecraft: Java Edition 启动器（Windows / macOS / Linux / Android），官网：[fcp.easonyin.xyz](https://fcp.easonyin.xyz)。

## 许可

本项目以 **GNU GPL-3.0** 发布，见 [`LICENSE`](./LICENSE)。上游致谢与第三方组件见 [`NOTICE`](./NOTICE)。

- 启动与下载核心：以 [HMCL](https://github.com/HMCL-dev/HMCL) / [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher) 一系 **GPL** 能力为底。
- [Plain Craft Launcher (PCL)](https://github.com/Meloong-Git/PCL) 仅作交互与体验参考，**不复制其源码**，也不受《PCL 分发有限许可》命名约束。

## 仓库结构

| 目录 | 说明 |
|------|------|
| `desktop/` | 桌面端（Win / macOS / Linux） |
| `android/` | 手机端（基于 FCL 方向的 Android 工程） |
| `web/` | 官网 `fcp.easonyin.xyz` |
| `docs/` | 架构、协议、验收清单 |
| `scripts/` | 构建与发布辅助脚本 |

## 目标功能（首期）

1. 恢复并稳定游戏 / 资源下载
2. 实用联机（参考 FCL 侧 EasyTier / Terracotta 等开源方案，遵守其许可）
3. 多端一致的账号与版本管理体验
4. 官网提供各端下载与协议说明

## 开发状态

| 版本 | 范围 |
|------|------|
| **v0.1（当前）** | Android：离线账号、LittleSkin（可填其他 Yggdrasil）、BMCLAPI 优先的历代版本下载。见 [`android/README.md`](./android/README.md)。 |
| 之后 | 桌面端 Win / macOS / Linux（[#5](https://github.com/nameefef/FCP-Launcher/issues/5)）、联机（[#4](https://github.com/nameefef/FCP-Launcher/issues/4)）、启动游戏进程、官方 Microsoft 登录。 |

v0.1 **不**接入官方 Microsoft 登录，也不借用其他启动器的 Azure Client ID。
