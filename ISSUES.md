# Initial issue backlog (create on GitHub after repo exists)

1. [meta] 确认许可证与 NOTICE（GPL-3.0 + FCL/HMCL 致谢；PCL 仅参考）
2. [site] 站起 fcp.easonyin.xyz（CF DNS + HTTPS；当前 TLS 不通）
3. [android] 基于 FCL 恢复/增强下载链路
4. [android] 联机能力（评估 FCL EasyTier/Terracotta 等）
5. [desktop] Win/macOS/Linux 骨架（HMCL 系或自研壳，禁止合入 PCL 源码）
6. [web] 官网下载页与说明（对接 @3d建模 首页 3D）
7. [qa] 各端冒烟清单（下载、启动、联机、协议署名页）

## v0.1 decisions (issues #3, #8, #9)

Shipped on Android first. Desktop (#5) and multiplayer (#4) stay open.

- Offline accounts use the HMCL / FCL `OfflinePlayer:` UUID. No crack / online-bypass tooling.
- Third-party login is LittleSkin (API root from https://manual.littleskin.cn/yggdrasil/ : `https://littleskin.cn/api/yggdrasil`). Advanced users can paste another authlib-injector / Yggdrasil URL, including hosts that send `X-Authlib-Injector-API-Location`.
- Official Microsoft login is out of v0.1. Do not block on a Mojang client-id whitelist, and do not embed another launcher's Azure client id.
- Version lists and files try BMCLAPI2 first (`https://bmclapi2.bangbang93.com`) and fall back to Mojang. About must keep the BMCLAPI credit. No extra unknown mirrors.

