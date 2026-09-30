# Colin投屏— Android 手机投屏到特斯拉车机

**面向特斯拉车机浏览器的 Android 手机投屏项目**，可将高德、百度等手机导航应用的画面显示在特斯拉中控屏上。
手机开启热点，特斯拉连接后，在车机浏览器打开 App 显示的 HTTPS 地址即可查看共享画面，无需在车机安装应用。
默认使用 H.264 低延迟视频，也可在手机端选择 JPEG 模式；开启无障碍反控后，还可通过车机网页操作手机。

## 功能

- **针对特斯拉投屏**：内置特斯拉访问代理，通过 VpnService 配合域名和 HTTPS 服务，适配车机浏览器的访问方式。
- **手机导航上车机**：共享高德、百度等导航应用画面，支持横竖屏自适应和适应/铺满切换。
- MediaProjection 屏幕共享，Android 8.0（API 26）及以上。
- H.264 硬件编码、WSS + WebCodecs 播放，以及 JPEG 兼容模式。
- 性能信息与静态季节背景。
- 可选无障碍反控：点击、长按、拖动及返回/主页/最近任务。

特斯拉车型、车机软件版本、浏览器能力和手机系统会影响兼容性，本项目不保证所有组合均可用。

## 使用前必读

**当前版本适合受信任热点环境下自用，不适合直接公开分发可用 APK。**

- 观看和反控尚无真正的配对认证。能连接服务的人可能观看共享画面并发送操作。
- 自用 APK 会包含本地 TLS 私钥；不要将其上传 GitHub Releases 或公开分发。
- 仓库不包含证书、私钥或部署凭据。全新克隆可编译，但需配置自己的域名和证书才能投屏。

详见 [安全说明](SECURITY.md) 和 [部署指南](docs/SETUP.md)。

## 从源码构建

准备 JDK 17、Android SDK 34、Build Tools 34.0.0；工程使用 AGP 8.3.1 和 Gradle 8.4。
设置 `JAVA_HOME`，通过 Android Studio 配置 SDK，或创建本地 `local.properties`。
JavaScript 语法测试需要 Node.js。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug --console=plain
```

Linux/macOS：`bash gradlew testDebugUnitTest assembleDebug --console=plain`。
产物：`app/build/outputs/apk/debug/app-debug.apk`。
GitHub Actions 自动运行单测与构建，不导入私钥、不发布 APK。

复制 `navmirror.local.properties.example` 为 `navmirror.local.properties`，填写自己的域名，
再按 [部署指南](docs/SETUP.md) 准备 DNS 与 TLS 材料。不要使用占位域名 `example.invalid`。

## 特斯拉投屏自用流程

1. 配置自己的域名和证书，构建并安装自用 APK。
2. 手机开启热点，让特斯拉连接该 Wi-Fi。
3. 打开 App，首次按系统提示允许「特斯拉代理」的 VPN 授权；手动关闭后下次启动保持关闭。
4. 点「开始投屏」，按系统提示选择要共享的应用或屏幕。
5. 在特斯拉车机浏览器打开 App 显示的 HTTPS 地址，即可查看手机导航画面。
6. 需要反控时，在手机系统设置中允许「NavMirror 反控」，并在网页开启反控。
7. 使用完毕后停止投屏与反控。

网页每次打开默认关闭反控；此开关不代替访问认证。

## 项目结构

- `app/src/main/java`：采集、编码、HTTP(S)/WSS 服务、VPN 与无障碍反控。
- `app/src/main/assets/player.js`：浏览器 H.264 播放器。
- `app/src/test`：现有单元测试。
- `docs/SETUP.md`：构建、证书签发/续期与上传检查。

## 许可证

本项目采用 [MIT 许可证](LICENSE)。第三方依赖仍遵循其各自许可证。
