# SSH Pad

面向 Android 平板的 SSH / Telnet 客户端（Jetpack Compose + Material 3）。

交互参考 [ServerBox](https://github.com/lollipopkit/flutter_server_box)：命令在终端里输入。终端用内置 xterm.js。

- 兼容 Android 8–17（minSdk 26，compile/target 36）
- 切到其他应用、长时间在后台，**已建立的连接保持**（前台服务 specialUse + CPU/Wi‑Fi 锁 + 8 秒心跳）
- 规避 `no such algorithm: X25519 for provider BC`
- 规避 `NetworkOnMainThreadException`

首次连接时请：允许通知；电池优化选「允许 / 无限制」。
