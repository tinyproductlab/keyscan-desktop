# Windows 打包与验证

KeyScan 的 Windows 发行物由 Compose Desktop 和 WiX 生成。主应用、Windows Hello 辅助程序以及 Native Messaging 主机均包含在安装包内，不依赖用户另外安装 JDK。

## 构建

在 PowerShell 中设置 JDK 17 后执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot'
& 'C:\Users\Administrator\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat' `
  --no-daemon --no-configuration-cache `
  :desktop-ui:test :shared-core:test :native-host:test `
  :desktop-ui:createDistributable :desktop-ui:packageExe :desktop-ui:packageMsi
```

输出位置：

- `desktop-ui/build/compose/binaries/main/exe/KeyScan-0.1.0.exe`
- `desktop-ui/build/compose/binaries/main/msi/KeyScan-0.1.0.msi`
- `desktop-ui/build/compose/binaries/main/app/KeyScan/`（免安装检查用应用镜像）

## 发布校验

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\packaging\windows\Verify-WindowsDistribution.ps1
```

校验会检查主程序及运行时、Windows Hello 资源、独立 Native Messaging 主机、浏览器注册脚本、EXE/MSI，并实际发送一帧未授权的 Native Messaging 请求，确认主机正确拒绝。通过后生成：

`build/release/windows/SHA256SUMS.txt`

浏览器主机使用独立的精简运行时，以保证 Chrome、Edge、Brave 和 Firefox 所需的标准输入/输出二进制帧不会经过 GUI 启动器。正式注册仍需各浏览器商店发布后的扩展 ID。

发布前还必须在干净 Windows 用户环境检查安装、升级、卸载、开始菜单快捷方式，并对最终安装包执行代码签名；未完成这些步骤时不得称为正式发布版。
