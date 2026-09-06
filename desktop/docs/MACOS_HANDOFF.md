# KeyScan macOS 开发与验证交接

本文用于在 Mac 实机继续 KeyScan 桌面版开发。Windows 与 macOS 共享业务、加密和安全备份核心；Keychain、Touch ID、Safari 包装、签名与公证必须在 macOS 上实现和验证。

## 不能改变的兼容性约束

- PIN、数据保护密钥、根密钥派生、数据库密钥信封和 V5/V6 安全备份格式必须与 Android 保持兼容。
- 同一保险箱在 Android、Windows、macOS 上使用时，PIN 与数据保护密钥必须完全一致；任意差异都会导致无法解密。
- Keychain 和 Touch ID 仅用于本机快速解锁，不能取代 PIN 或数据保护密钥，也不能生成新的跨平台根密钥。
- 桌面版不设置 Pro 限制，macOS 与 Windows 的功能策略一致。

## Intel Mac 与 Xcode 15.2 前置条件

- Intel Mac，macOS 版本应满足 Xcode 15.2 支持范围。
- Xcode 15.2 与 Command Line Tools：`xcode-select --install`，随后执行 `sudo xcodebuild -license accept`。
- JDK 17（与 Windows 构建一致）。确认：`java -version`。
- Git，以及 IntelliJ IDEA 或 Android Studio（Kotlin／Compose 插件）。
- 对外发布需要 Apple Developer 账号、Developer ID Application 证书，以及 notarization 的 App Store Connect API Key。

## 首次构建与运行

在 `KeyScan-Desktop` 根目录运行：

```bash
./gradlew --no-daemon :shared-core:test :desktop-ui:test
./gradlew :desktop-ui:run
./gradlew :desktop-ui:packageDmg
```

首次启动应要求创建 4–6 位 PIN 和数据保护密钥。之后验证同一 PIN／密钥可以重新解锁；不得为了测试便利跳过或修改 Android 兼容的根密钥派生。

`packageDmg` 只能在 macOS 上运行。Windows Hello 辅助程序、DPAPI 浏览器桥接和 Windows 安装器资源不会被打入 macOS 应用。

## Keychain 与 Touch ID 的实现边界

1. 为 macOS 创建独立的 Keychain 凭据实现，敏感的本地快速解锁材料只能写入 Keychain。
2. Touch ID 成功后只能解封本机保存的快速解锁材料，再继续使用原有根密钥／数据库密钥信封建立会话。
3. 没有 Touch ID、用户取消、Keychain 条目损坏、系统策略拒绝或设备更换时，必须安全回退到 PIN + 数据保护密钥输入。
4. 锁屏、关闭快速解锁或切换用户时清除内存中的数据库密钥与临时字节；不得把 PIN、数据保护密钥或明文数据库密钥写入偏好设置、日志或备份。
5. 在至少一台支持 Touch ID 的 Mac 上验证：注册、重启后解锁、取消、删除 Keychain 条目、关闭功能，以及 PIN 回退。

## Safari 浏览器扩展

Safari 必须使用原生 Safari App Extension 包装，不能直接复用 Chromium／Firefox 的 Native Messaging 清单。

- 保持“桌面端明确授权、精确 HTTPS Origin、一次性填充、60 秒超时、不自动提交”的安全策略。
- Safari 扩展与宿主只能使用 Apple 支持的 App Extension 通道；不能向任意网页开放桌面进程接口。
- 正式发布前为 Safari 扩展标识建立严格白名单，并验证非 HTTPS、Origin 不匹配、锁屏、超时和导航竞态均拒绝填充。

## 打包、签名与公证

完成 macOS 应用包后，依次验证：

1. 干净 macOS 用户环境中的安装和启动。
2. 首次设置、锁屏、PIN 回退与 Touch ID（可用时）。
3. Android V6 备份恢复，以及桌面 V6 备份创建。
4. Safari 扩展安装／卸载和授权填充。
5. Developer ID 签名、Hardened Runtime 与 `codesign --verify --deep --strict`。
6. 使用 `xcrun notarytool` 公证并用 `xcrun stapler staple` 装订。
7. 在未安装开发工具的另一台 Mac 上验证 Gatekeeper 启动、升级和卸载。

## 当前状态

`DesktopPlatform` 已确保 macOS 不获取 Windows HWND、不启动 DPAPI 浏览器桥接、也不显示 Windows Hello 控件。Keychain、Touch ID、Safari 包装、签名与公证仍需按以上清单在 Mac 实机完成；不能以 Windows 构建或模拟结果替代。
