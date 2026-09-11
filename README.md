# SSH Pad

面向 Android 平板的 SSH / SFTP 客户端（Jetpack Compose + Material 3）。

- 兼容 Android 8–17（minSdk 26，compile/target 36，可在 Android 16 / 17 运行）
- 切分屏、改窗口大小 **不断开**
- 规避 `no such algorithm: X25519 for provider BC`

## 上传到 GitHub 并用 Actions 出 APK

1. 解压本包，得到 `ssh-pad` 目录
2. 在 GitHub 新建空仓库（不要勾选 README / .gitignore / License）
3. 把 **`ssh-pad` 目录里的全部文件** 上传到仓库根目录（要能看到 `.github/workflows/android.yml`）
4. 打开仓库 **Settings → Actions → General**，允许 Actions
5. 打开 **Actions → Build APK → Run workflow**（第一次 push 也会自动跑）
6. 绿勾后进入该次 run，下载 Artifact `ssh-pad-debug`，解压即 `app-debug.apk`

本机不需要 Android SDK。工作流会装 JDK 17 和 Android SDK 36。

## 功能

| 能力 | 说明 |
| --- | --- |
| SSH | 密码登录，交互 shell |
| SFTP | 列目录、上传、下载 |
| 会话保活 | 前台服务 + 进程单例 + 15 秒心跳 + `configChanges` |
| 加密 | 卸掉系统阉割 BC，装完整 BouncyCastle |

密钥登录未做；主机密钥默认信任，适合内网自用。安装后请允许通知。
