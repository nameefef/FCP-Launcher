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

## 首期里程碑

- M0：本仓库骨架 + NOTICE + 空桌面/安卓/网页占位
- M1：官网可 HTTPS 访问，提供占位下载页
- M2：Android 可装包 + 基础启动/下载冒烟
- M3：Desktop 三端可装包 + 基础启动/下载冒烟
- M4：联机 MVP + 回归清单
