# 从源码构建与自用部署

## 构建环境

- JDK 17、Android SDK Platform 34、Build Tools 34.0.0。
- 工程使用 AGP 8.3.1 与 Gradle Wrapper 8.4。
- 设置 `JAVA_HOME`，并由 Android Studio 生成 `local.properties`；或填写 `sdk.dir=D:/你的SDK目录`。
- 单测中的 JavaScript 语法检查需要 Node.js；GitHub Actions 已配置 Node.js。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug --console=plain
```

Linux/macOS 使用 `bash gradlew testDebugUnitTest assembleDebug --console=plain`。
产物为 `app/build/outputs/apk/debug/app-debug.apk`。全新克隆可以编译、运行单测，
但没有 TLS 材料时不能启动 HTTPS 投屏；CI 不导入证书、不上传 APK。

## 域名配置

复制 `navmirror.local.properties.example` 为 `navmirror.local.properties`，修改：

```properties
domain=你的域名.dedyn.io
```

只填写主机名，不含协议、端口或路径。Gradle 配置优先级：
`-PnavmirrorDomain=域名` > `NAVMIRROR_DOMAIN` 环境变量 > 本地文件 > `example.invalid`。
默认值只是占位域名，不能用于实际投屏。

域名的公开 A 记录需指向 `100.99.88.77`，证书必须匹配该域名。
App 中的特斯拉代理将该地址注册为手机本地地址。
这是现有设备环境验证过的访问方案，其他车机或系统版本需另行验证。

## 自备 TLS 材料

将自己域名的完整证书链和 PKCS#8 私钥分别放入：

```text
app/src/main/assets/tesla_cert.pem
app/src/main/assets/tesla_key.pem
```

这些文件和旧版 `colinmirror.p12` 均被 Git 忽略，但会被打包进自用 APK。
任何拿到 APK 的人都能提取它们，因此不要公开分发含证书私钥的 APK。
Release 合并 assets 时发现 TLS 材料将拒绝构建；确需自用 Release 时才显式传入
`-PallowPrivateTlsInRelease=true`。这一参数不意味着 APK 适合公开发布。

## WSL 首次签发与续期（deSEC）

先准备自己控制的 deSEC 域名、受限 API token，并在 WSL 中安装 Certbot 和
`certbot-dns-desec` 插件。参数见 [插件官方说明](https://github.com/desec-io/certbot-dns-desec)。
其他 DNS 提供商需要使用其对应插件；本项目续期脚本只针对 deSEC。

在 WSL 中设置 `NAVMIRROR_DOMAIN`，并使用受保护的目录持久保存证书：

```bash
export NAVMIRROR_DOMAIN='你的域名.dedyn.io'
export CERTBOT_HOME="$HOME/.local/share/navmirror/certbot"
umask 077
mkdir -p "$HOME/.config/navmirror"
read -r -s -p 'deSEC token: ' DESEC_TOKEN; printf '\n'
printf '%s\n' "$DESEC_TOKEN" > "$HOME/.desec-token"
printf 'dns_desec_token = %s\n' "$DESEC_TOKEN" > "$HOME/.config/navmirror/desec.ini"
unset DESEC_TOKEN
certbot certonly --authenticator dns-desec \
  --dns-desec-credentials "$HOME/.config/navmirror/desec.ini" \
  --cert-name "$NAVMIRROR_DOMAIN" -d "$NAVMIRROR_DOMAIN" \
  --config-dir "$CERTBOT_HOME/conf" --work-dir "$CERTBOT_HOME/work" \
  --logs-dir "$CERTBOT_HOME/log"
```

首次签发按 Certbot 提示完成。把这两个 `export` 配置保存在 WSL 的本地环境配置中，
或每次运行续期命令前设置。不要把 token 放入仓库、文档或命令行参数。

然后在项目目录执行：

```bash
bash renew_cert.sh
```

脚本按 `DESEC_TOKEN`、兼容变量 `DESC_TOKEN`、`~/.desec-token` 的顺序读取凭据。
临时凭据使用随机文件名、限制权限并在退出时清理。
脚本只续期指定域名，未到续期窗口时直接复用现有证书，不强制重复申请。
输出目录由 `CERT_OUT` 指定，默认 `/tmp/certs`。
为兼容旧部署，未设置 `CERTBOT_HOME` 时仍使用 `/tmp/certbot`；该目录不适合长期保存证书。

Windows 的 `一键续期打包.bat` 会读取本地域名配置、转换工程路径、续期、运行单测并构建。
WSL 需预先完成上述首次签发，并正确设置证书目录。
批处理通过 Bash 登录模式运行脚本，`CERTBOT_HOME` 应配置在 WSL 的登录配置文件
（如 `~/.profile`，若存在 `~/.bash_profile` 则在其中配置或加载 `~/.profile`）。
只有一台已授权的 adb 设备时自动安装，多台设备时跳过安装。
Debug APK 会复制到桌面，仅供自用。

## 上传 GitHub 前

1. 确认历史上泄露过的 API token 已在服务商处撤销。
2. 检查 `git status --short` 与 `git diff --cached`，确认暂存区不含密钥、APK、本地配置。
3. 开发记录与设计计划只保留在本地；公开前检查旧提交中是否仍含这些记录或个人部署信息。
4. 执行单测与构建；GitHub Actions 会在 push/PR 时再次检查。
5. 仓库采用 MIT 许可证（见 `LICENSE`）；第三方依赖遵循其各自许可证。

上传源码不会解决观看/反控认证问题。当前仅适合受信任的热点环境；
公开分发可用 APK 前仍需实现手机确认配对、会话认证和独立的私钥管理方案。
