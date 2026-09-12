# SSH Pad 1.4.0

切到其他应用掉线的根因（已修）：

1. 前台服务同时声明了 `dataSync|specialUse`。Android 15+ 对 **dataSync 有超时**，超时后服务不再算前台，进程会被冻结，心跳停、TCP 被 NAT/服务器掐掉。
2. Activity `onStop` 里 **unbindService**。切走应用就解绑，部分机型会把只靠绑定活着的服务拆掉。
3. SSH 的 **stderr 读到 EOF 被当成整条连接死了**（PTY 下 stderr 经常是空的）。
4. 终端 WebView 被系统杀掉后，输出没缓存，回来像「断了」。

现改为：只用 `specialUse` 前台服务、切走时重新 ensure FGS、stderr 不再误杀、滚动缓冲回放、8 秒心跳。

「日志」页可保存完整会话和生命周期事件到本地。
