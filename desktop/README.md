# KeyScan Desktop

KeyScan 的 Windows 与 macOS 跨平台桌面项目。

## 技术方案

- 开发语言：Kotlin
- UI：Compose Multiplatform
- Windows 与 macOS 共用业务核心和主要界面
- Windows Hello、Windows 安全存储、Touch ID、macOS Keychain 等能力分别原生实现
- 桌面版不设置 Pro，全部桌面功能开放

## 目录

```text
KeyScan-Desktop
├── shared-core       跨平台数据、加密、TOTP、WebDAV、导入导出
├── desktop-ui        Windows/macOS 共用桌面界面
├── windows-platform  Windows Hello、安全存储、托盘及安装包
├── macos-platform    Touch ID、Keychain、菜单栏、签名及公证
├── design            KeyScan 品牌和桌面设计资源
└── docs              产品范围、加密协议和开发说明
```

## 当前开发状态

- 已创建 Gradle 多模块工程（`shared-core`、`desktop-ui`）。
- 已创建可编译的 Compose Desktop 首次安全设置与主导航界面。
- 已实现与 Android 字节兼容的 PIN + 数据保护密钥根密钥派生，并加入测试。
- 已实现数据库密钥信封、安全设置持久化、重启解锁和手动锁屏。
- 已实现版本化AES-256-GCM本地保险箱，以及密码记录新增、读取、更新和删除数据层。
- 已加入密码搜索与编辑；TOTP已支持加密保存、实时验证码、倒计时和RFC 6238 SHA-1测试向量。
- 已实现安全保险箱项目CRUD、搜索、附件元数据和附件内容流式AES-GCM加密。
- 已实现Android兼容的V5/V6安全备份加密容器与完整性测试。
- 已实现Android业务JSON版本5编解码及桌面领域模型映射，保留全部已支持字段。
- 已实现Android一致的V6 ZIP流式打包与读取：payload.json首项、附件contentReference和安全路径校验。
- 已连接真实保险箱一致性快照，实现V6本地备份原子创建、附件流式打包、SHA-256记录和只读完整性验证。
- 已实现V6事务式替换恢复：完整验证后提交、附件重新加密、失败回滚旧快照与旧附件。
- 已实现本地备份历史、SHA-256状态检测和桌面安全备份/替换恢复页面。
- 下一步：双WebDAV、密码分组/历史、附件文件选择器及Android黄金文件交叉验证。

运行开发版：

```powershell
.\gradlew.bat :desktop-ui:run
```

运行核心测试：

```powershell
.\gradlew.bat :shared-core:test
```

## 当前开发顺序

1. 在 Windows 安装 IntelliJ IDEA、JDK 17 和 Kotlin Multiplatform 插件。
2. 建立可运行的 Compose Multiplatform 桌面工程。
3. 实现首次设置 PIN 和数据保护密钥。
4. 实现本地加密保险库和桌面首页。
5. 实现密码账本、TOTP、安全保险箱和安全备份。
6. 接入 Windows Hello。
7. 到 Mac 上补充 Keychain、Touch ID、签名及公证。

## 跨平台凭据规则

同一保险库在 Android、iOS、Windows 和 macOS 上必须使用相同的 KeyScan PIN 和数据保护密钥。任何一项不一致，都不能解密其他平台创建的安全备份。

Windows Hello 和 Touch ID 只用于本机快捷验证，不能替代原始 PIN 或数据保护密钥。
