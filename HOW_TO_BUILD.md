# 易控车机版(Easycontrol_For_Car)编译说明

### 此方法编译的apk不包含签名部分设备可能无法使用

### 注意：需要GitHub账号

<img src="pic/build/1-2.webp">

<img src="pic/build/3-7.webp">

<img src="pic/build/8-9.webp">

## 项目有更新时切换到对应分支后按下图所示操作后重新执行上面的操作
<img src="pic/build/update.webp">

---

## 🤖 用 GitHub Actions 构建（本 fork 推荐）

本仓库内置工作流：`.github/workflows/Easycontrol_For_Car.yml`。

**步骤**

1. 打开仓库的 [Actions](https://github.com/birdnofoots/Easycontrol_For_Car/actions) 页面
2. 左侧选择 **Easycontrol_For_Car** 工作流
3. 右上角 **Run workflow** → 选择分支 `main` → **Run workflow**
4. 等待构建完成（约 1~3 分钟），在运行详情页底部 **Artifacts** 下载 `app-debug`（保留 14 天）

**产物**

- `app-debug.apk`：debug 签名，可直接安装
- 同时会构建 `:server:copyRelease`（server 端 jar 会打包进 APK 资源）

> ⚠️ **注意**：fork 仓库的 push / pull_request **不会自动触发** Actions（GitHub 安全策略，避免 fork 滥用上游额度）。
> 因此每次推送代码后，需要**手动 Run workflow**。
> 若希望 push 自动触发，可在仓库 `.github/workflows/` 中保留 `workflow_dispatch` 并手动触发，或将仓库转为非 fork（不建议，会丢失与上游的关联）。

**常见构建失败原因**

| 报错 | 原因 | 处理 |
|---|---|---|
| `cannot find symbol`（Java 编译） | 代码引用了不存在的 API/方法 | 本地无 Android SDK 时难以发现，看 Actions 日志修正后重新触发 |
| `Failed to restore gradle cache` | GitHub 缓存服务抖动 | 属 warning，不影响构建 |
| `Node.js 20 is deprecated` | action 版本较旧 | 属 warning，功能正常 |

---

## 🔐 签名（务必先读）

### 为什么重要

APK 的签名决定了**能否覆盖安装**。签名不同的包会被系统拒绝：

```
INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package ... signatures do not match newer version
```

本项目早期在 GitHub Actions 上构建时，**每次都会生成随机 debug 密钥**，
导致每次产出的 APK 签名都不同，无法互相覆盖安装。
现在已改为**固定密钥**：密钥库经 base64 存入仓库 Secrets，
构建时还原到临时目录并由 Gradle 读取。

### 仓库 Secrets（已配置，勿删）

| Secret | 说明 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 密钥库文件的 base64 |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库口令 |
| `ANDROID_KEY_ALIAS` | 别名（`easycontrol`） |
| `ANDROID_KEY_PASSWORD` | 密钥口令 |

> ⚠️ **务必备份密钥库与口令**。一旦丢失，就无法再发布可覆盖安装的更新
> （用户必须卸载重装，且数据会丢失）。
> 官方 APK 的证书 SHA1 为 `C9:E6:08:39:91:A9:C6:3D:7A:0C:DE:11:05:03:48:99:E8:1B:65:13`。

### 本地构建（使用同一密钥签名）

Gradle 从环境变量读取签名配置，因此本地也可产出与 CI 完全一致的签名包：

```bash
cd easycontrol
export ANDROID_KEYSTORE_PATH=/绝对路径/easycontrol-release.jks
export ANDROID_KEYSTORE_PASSWORD='你的口令'
export ANDROID_KEY_ALIAS=easycontrol
export ANDROID_KEY_PASSWORD='你的口令'
./gradlew :server:copyRelease
./gradlew :app:assembleRelease   # 或 :app:assembleDebug
```

未设置 `ANDROID_KEYSTORE_PATH` 时，构建仍可成功，但会回退到随机 debug 密钥
（仅适合临时测试，不能用于发布）。

### 校验签名

```bash
keytool -printcert -jarfile app-release.apk    # 应输出上面的 SHA1
```

### 关于 R8 混淆

`release` 变体当前**关闭**了 `minifyEnabled` / `shrinkResources`。原因：
开启后真机实测启动即崩（`NullPointerException` 于 `MainActivity.onPause()`，
混淆后字段 `x3.i.b`），推断为 R8 full mode 的类合并/静态字段优化破坏了初始化路径。
本项目依赖 hidden API bypass 与反射（`libadb`、`sun-security-android`），
混淆风险较高。若日后要开启，必须先补齐 keep 规则并做真机回归测试。
