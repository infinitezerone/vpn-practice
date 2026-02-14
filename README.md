# Pratice VPN (Android)

一个用于 **代理连通性验证 / 路由实验 / VPN 转发调试** 的 Android 应用。  
支持通过 `VpnService` 把设备流量转发到上游代理（SOCKS5 或 HTTP）。

## 功能

- 上游代理接入：
  - `SOCKS5`
  - `HTTP CONNECT`
- HTTP 转发策略：
  - `CompatFallback`（兼容回退）
  - `StrictProxy`（严格走代理）
- 路由模式：
  - `Bypass`（排除选中 App）
  - `Allowlist`（仅选中 App）
- 代理 bypass 域名规则（逗号分隔）
- 开机自动重连开关
- 运行时日志与状态查看
- **局域网自动发现代理并自动连接**
  - 扫描同网段常见代理端口：`7890` / `7891` / `8888`
  - 识别 HTTP / SOCKS5 后自动填充并发起连接

## 环境要求

- Android Studio（推荐最新版）
- JDK 11
- Android SDK（`compileSdk 36`, `minSdk 26`, `targetSdk 36`）

## 构建与测试

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

连接设备/模拟器后可运行：

```bash
./gradlew :app:connectedDebugAndroidTest
```

## 一键本地 SOCKS5 测试

项目自带本地 SOCKS5（无认证、CONNECT only）和一键测试脚本：

- `tools/local_socks5_proxy.py`
- `tools/run_local_socks5_forwarding_test.sh`

直接执行：

```bash
tools/run_local_socks5_forwarding_test.sh
```

脚本会自动：

1. 启动本地 SOCKS5（默认 `0.0.0.0:19027`）
2. 跑 `VpnProxyForwardingTest`（默认通过 `10.0.2.2:19027`）
3. 清理代理进程并输出日志位置

## 使用说明（App 内）

1. 填入代理 `Host` / `Port`
2. 选择 `SOCKS5` 或 `HTTP`
3.（可选）设置 HTTP 模式、Bypass 规则、App 路由
4. 点 `Test Connection`
5. 点 `Start` 建立 VPN

也可以使用：

- `Scan LAN & Auto Connect`

自动扫描局域网代理后直接连接。

## 局域网扫描注意事项

若提示扫描不到代理，通常是网络环境或代理监听配置问题：

- 代理软件未监听 LAN（例如 Clash 需开启 `allow-lan`）
- 电脑防火墙未放行代理端口
- 手机与电脑不在同一网段/VLAN
- AP Isolation 导致终端互相不可达
- 在 Android Emulator 中，建议优先使用 `10.0.2.2` 访问宿主机

## 当前限制

- 目前仅支持**无认证**上游代理（不支持用户名/密码认证）
- 设计目标是调试和实验，不是生产级 VPN 客户端

## 代码结构（核心）

- `app/src/main/java/com/infinitezerone/pratice/MainActivity.kt`：Compose UI
- `app/src/main/java/com/infinitezerone/pratice/vpn/AppVpnService.kt`：VPN 生命周期与转发编排
- `app/src/main/java/com/infinitezerone/pratice/vpn/HttpConnectSocksBridge.kt`：HTTP 代理桥接
- `app/src/main/java/com/infinitezerone/pratice/discovery/LanProxyScanner.kt`：局域网代理扫描

## 免责声明

请仅在你有授权的网络和设备环境中使用本项目。  
请遵守所在地区法律法规与公司安全规范。
