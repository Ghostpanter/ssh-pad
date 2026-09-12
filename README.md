# SSH Pad 1.6.0

1.5.0 日志结论：切走约 5 秒后 `ssh-stdout EOF session=false`。进程没死，是公网 socket 被 `bindSocket` 绑到 Network 120，国行后台限制该网卡导致 TCP RST。`holdNetwork` 因缺少 `CHANGE_NETWORK_STATE` 失败。`watch` 所在 PTY 随会话 SIGHUP，重连会丢内容。

1.6.0：
- SSH 跑在独立进程 `:session`（常驻内存）
- 国行：mediaPlayback 静音循环 + 悬浮球 + 高优先级通知
- 公网不再 bindSocket；补 CHANGE_NETWORK_STATE
- 切回时把会话进程里缓存的终端缓冲回放到 xterm（含 watch 输出）
