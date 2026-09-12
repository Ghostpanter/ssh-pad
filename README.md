# SSH Pad 1.5.0

1.4.0 日志已确认：进程没被杀，是 `ssh-stdout` EOF。
截图里 VPN 开着、主机是 `192.168.1.1`。切到其他应用时 VPN 抢走默认路由，局域网 TCP 被重置。

1.5.0：socket 在 connect 前绑到 Wi‑Fi/以太网（绕开 VPN），TCP_KEEPIDLE=5s，stdout 死后若会话还在则重开 PTY，否则自动重连最多 3 次。主机名不再预填。
