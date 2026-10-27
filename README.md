# 吃药酒馆

[SillyTavern](https://github.com/SillyTavern/SillyTavern) 的非官方安卓封装。安装后即可在手机上运行 SillyTavern，不需要 Termux。

本项目与 SillyTavern 官方无关。

## 功能

- 内置 Node.js、git 和 npm。SillyTavern 在应用内运行，界面直接在应用中打开。
- 每次启动时检查 SillyTavern 更新（跟随官方 `release` 分支），有新版本就自动下载安装。新版本启动失败时，自动回退到上一个版本。
- 扩展的安装和「更新全部扩展」与 SillyTavern 原本的用法一致。
- 支持服务端插件：可以从 git 仓库地址或 zip 安装，然后启用、停用、更新或删除。
- 可以导入已有数据（SillyTavern 文件夹、`data` 文件夹、`default-user` 文件夹，或本应用导出的备份），也可以把数据导出到「下载」。
- 应用新版本发布时，会在应用内提示更新。
- 可以设置 GitHub 加速地址和 npm 源。

## 系统要求

- Android 8.0 及以上，arm64 设备
- 首次启动需要下载 SillyTavern，约 150 MB

## 已知限制

- 应用在前台时，SillyTavern 保持运行，运行期间会显示一条常驻通知。切到后台后，系统仍可能结束 SillyTavern；重新打开应用会重新启动。
- 服务端插件的依赖安装时会跳过安装脚本（应用数据目录中的文件无法执行），依赖原生模块的功能无法使用。`bcrypt` 和 `canvas` 会自动替换为纯 JS 或预编译的实现；其他原生模块（如 `sharp`、`better-sqlite3`、`bufferutil`）仍会安装，但无法加载，安装完成时会列出这些依赖。很多原生依赖只是可选的加速模块（例如 `ws` 的 `bufferutil`），缺失时插件仍能正常工作。
- 端口固定为 8000。更换端口后，浏览器本地存储中的部分 SillyTavern 界面设置会恢复默认。

## 许可证

本项目以 [GNU AGPL-3.0](LICENSE) 发布，与 SillyTavern 相同。

---

## 维护

### 目录

```
android/                 安卓应用（Kotlin，无 AndroidX）
tools/node-android/      交叉编译 Node.js（Docker + NDK）
tools/git-android/       交叉编译 git（含 OpenSSL、curl）
tools/natives.mjs        把编译好的 Node、git、npm 放进 android/build/natives/
tools/pack-st.sh         打包 SillyTavern（源码 + 生产依赖，带启动冒烟测试）
tools/manifest.mjs       更新 latest.json
.github/workflows/       定时打包 SillyTavern
```

### 发布结构

所有文件都放在本仓库的 Release 中：

| Release | 内容 | 来源 |
|---|---|---|
| `manifest` | `latest.json`，应用启动时读取 | 两个工作流共同更新 |
| `st-<版本>-<构建号>` | SillyTavern 包 | `st-package.yml`，每 6 小时检查一次上游 |
| `v<版本>` | APK | `android.yml`，推送 `v*` 标签时触发 |
| `natives` | `natives.zip`（Node、git、npm） | 手动上传 |

`latest.json` 的格式见 `android/app/src/main/java/xyz/chiyao/tavern/Manifest.kt`。SillyTavern 包里的 `minShell` 表示运行这个包所需的最低应用 versionCode，由仓库变量 `MIN_SHELL` 控制。如果某个 SillyTavern 版本需要新版应用才能运行，先发布应用，再调高这个值。

### 编译 Node 和 git

需要 Docker，编译结果缓存在卷 `chiyao-node-android` 里。Node 单个架构的编译约需一小时。

```sh
docker run --rm -i -v chiyao-node-android:/work ubuntu:24.04 bash -s arm64 "$(cat tools/node-android/node.version)" < tools/node-android/build.sh
docker run --rm -i -v chiyao-node-android:/work ubuntu:24.04 bash -s arm64 < tools/git-android/build.sh
node tools/natives.mjs --zip
gh release upload natives android/build/natives.zip --clobber
```

模拟器调试需要 x86_64：把上面两条编译命令里的 `arm64` 换成 `x86_64` 再各运行一次，然后执行 `node tools/natives.mjs --x86_64`。

### 签名

所有 APK 必须使用同一个密钥签名，否则手机无法覆盖安装。生成一次即可，并且要妥善备份：

```sh
keytool -genkeypair -v -keystore release.keystore -alias tavern -keyalg RSA -keysize 4096 -validity 36500
```

密钥放在仓库外的 `../chiyao-tavern-signing/`（可用环境变量 `TAVERN_SIGNING_DIR` 改位置），里面是 `release.keystore` 和 `password.txt`：

```text
alias: tavern
store/key password: <密码>
```

### 发布应用

在本机编译、签名并发布，不经过 CI。先提交并推送代码，再运行：

```sh
node tools/release.mjs 1.0.3 "更新说明（会显示在应用的更新提示里）"
```

脚本会运行单元测试、编译签名版 APK、推送标签 `v1.0.3`、创建同名 Release，并更新 `manifest` 里的 `latest.json`（versionCode 在上一版基础上加 1）。需要已登录的 `gh`、Java 17，以及 `android/build/natives` 里的 Node 和 git（见上文）。

### 本地调试

```sh
cd android
./gradlew testDebugUnitTest assembleDebug
```

要让调试版读取本地的 `latest.json`，构建时设置环境变量 `TAVERN_MANIFEST_URL`（模拟器访问电脑用 `http://10.0.2.2:<端口>/latest.json`）。Fork 后发布时，发布脚本会自动把这个地址指向你自己仓库的 `manifest` Release。
