# SSH Pad

面向 Android 平板的 SSH / Telnet 客户端（Jetpack Compose + Material 3）。

交互参考 [ServerBox](https://github.com/lollipopkit/flutter_server_box)：命令在终端里输入，不是单独的发送框。终端用内置 [xterm.js](https://github.com/xtermjs/xterm.js)。

- 兼容 Android 8–17（minSdk 26，compile/target 36）
- 切分屏、改窗口大小 **不断开**
- 规避 `no such algorithm: X25519 for provider BC`
- 规避 `NetworkOnMainThreadException`（全部 socket 写都在后台线程）

## 功能

| 能力 | 说明 |
| --- | --- |
| SSH | 密码登录，交互 PTY（xterm-256color） |
| Telnet | 默认 23 端口，在终端里交互登录 |
| 终端 | 点终端即可输入；Esc/Tab/方向键/Ctrl-C 等快捷键 |
| SFTP | SSH 下列目录、上传、下载（Telnet 无文件传输） |
| 会话保活 | 前台服务 + 进程单例 + 心跳 + `configChanges` |

## 构建

GitHub Actions：push `main` 自动出 debug APK。Release 页可直接下载。
