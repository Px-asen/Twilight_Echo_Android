# 构建 Android 发布包

将 `JAVA_HOME` 指向 JDK 17 或更新版本，在仓库根目录执行：

```powershell
.\gradlew.bat verifyPortableCore :app:assembleRelease
```

本机已配置正式签名时，安装包生成于 `app/build/outputs/apk/release/app-release.apk`。首次配置使用根目录的 `signing.properties`，包含 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`；`storeFile` 相对仓库根目录解析。缺少此配置时沿用 Gradle 的未签名 release 构建，未签名 APK 不能直接分发安装。

当前签名密钥保存在 `.secrets/android-release/twilight-echo-release.p12`，别名为 `twilight-echo-release`。**请将密钥文件与 `signing.properties` 一起单独、安全备份。** 它们已被 Git 忽略，不应上传到 Release、提交到仓库或分享给安装用户；后续覆盖升级须继续使用同一密钥。

首次打包时已在当前 Windows 用户的 `.android/release-signing/TwilightEcho/` 目录另存一份签名资料与恢复说明。此副本也应作为私密资料备份。

发布前更新 `app/build.gradle.kts` 的 `versionName` 与递增的 `versionCode`，并更新 `RELEASE_NOTES.md`。共享 Core 的版本由 `core-lock.json` 单独记录。发布附件只需签名 APK 和面向用户的更新说明；R8 的 `mapping.txt` 应随对应版本私下归档，供崩溃日志还原使用。

已打包的本地交付目录为 `release/<version>/`。每个版本的 `BUILD_INFO.md` 记录源码、构建和验证结果。
