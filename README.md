# 易控车机版(Easycontrol_For_Car)

> **🚧 本仓库为 fork 版本（birdnofoots/Easycontrol_For_Car）**
>
> **项目源头（原版）**: <https://github.com/eiyooooo/Easycontrol_For_Car> by [@eiyooooo](https://github.com/eiyooooo)
>
> 本 fork 在原版基础上新增了 **多 IP/域名地址池** 支持，原版的所有功能保持不变。
> 原作者的版权与 GPL-3.0 许可证完整保留，详见 [LICENSE](./LICENSE)。
>
> 反馈原版问题请到 [原仓库 Issues](https://github.com/eiyooooo/Easycontrol_For_Car/issues)，
> 本 fork 的问题请到 [本仓库 Issues](https://github.com/birdnofoots/Easycontrol_For_Car/issues)。

[![GitHub license](https://img.shields.io/github/license/eiyooooo/Easycontrol_For_Car.svg)](https://github.com/eiyooooo/Easycontrol_For_Car/blob/main/LICENSE)
[![GitHub release](https://img.shields.io/github/release/birdnofoots/Easycontrol_For_Car.svg)](https://github.com/birdnofoots/Easycontrol_For_Car/releases)

---

## ✨ 本 fork 新增功能 (v1.7.0+)

### 多 IP/域名地址池（Address Pool）

**痛点**：原版每个设备只能填一个地址。但同一台手机/车机在不同网络环境下 IP 经常变化（家里、公司、咖啡馆、公共 WiFi、4G/5G…），每次切换网络都要去软件里改一遍 IP，非常麻烦。

**解决**：现在可以给一个设备配置 **多个 IP/域名**，连接时**任一可用即视为成功**。

#### 使用场景

| 场景 | 地址池示例 |
|---|---|
| 家里 | `192.168.1.10:5555` |
| 公司 | `10.0.0.15:5555` |
| 4G 热点（域名） | `myphone.ddns.net:5555` |
| 路由器 DNS 解析 | `phone.local:5555` |

#### 使用方法

1. **添加设备** → "地址池"区域 → 输入第一个地址
2. 点 **"+ 添加地址"** 增加更多行
3. 同一行的 **"删除"** 按钮移除该地址（至少保留一行）
4. 点 **"扫描"** 把局域网扫描到的 IP 自动填到第一个空行
5. **保存** → 设备即可"任一地址连通即连接"

#### 连接策略

- **并行尝试**：所有地址同时发起连接
- **首个成功即用**：用 6s 整体超时（不是 N×5s）
- 失败：弹"无法连接"提示，可以改地址

#### 数据迁移

- 老版本（V1.6.10）数据库自动升级（v19 → v20）
- 旧的 `address` 字段自动包装为单元素地址池
- 无需手动迁移数据

---

## ⚠️ 原版声明（来自原作者）

> #### "易控车机版"**永久免费**，不会收取任何费用，也不会强制要求捐赠。请勿相信任何收费的"易控车机版"，以免上当受骗
>
> #### 本软件开源许可证为**GPL-3.0**，任何人可以免费使用、修改、分发，但请遵守开源许可证的规定
>
> #### 本项目**原版**唯一地址为 <https://github.com/eiyooooo/Easycontrol_For_Car>
>
> 反馈原版问题请到 [Issues](https://github.com/eiyooooo/Easycontrol_For_Car/issues)，功能建议请到 [Discussions](https://github.com/eiyooooo/Easycontrol_For_Car/discussions)

---

## 📖 原版简介

本软件可实现手机屏幕镜像到车机、手机应用流转到车机

- **车机支持**：能够安装第三方软件、基于安卓系统的车机
- **手机支持**：能够打开开发者调试(ADB)功能、基于安卓系统的手机

## 📥 安装包

- **本 fork（含多地址功能）**: <https://github.com/birdnofoots/Easycontrol_For_Car/releases>
- **原版**: <https://github.com/eiyooooo/Easycontrol_For_Car/releases>

## 📘 使用说明

- 建议在软件内阅读
  - 首次打开软件将会显示
  - 亦可在软件设置中再次打开
- [HOW_TO_USE.md](./HOW_TO_USE.md)

## 🛠️ 编译

- [HOW_TO_BUILD.md](./HOW_TO_BUILD.md)
- 或直接用本仓库的 GitHub Actions：`.github/workflows/Easycontrol_For_Car.yml`

## 📜 变更日志 (Changelog)

### v1.7.0 (2026-10-15) — 本 fork 版本
- 🆕 **多 IP/域名地址池**：单个设备可配置多个连接地址，任一可用即连接
- 🆕 动态行 UI：添加/删除地址行无需重新打开对话框
- 🆕 扫描局域网 IP 一键加入地址池
- 🆕 数据库自动迁移（v19 → v20）
- 🆕 兼容旧版本单地址数据
- 🔧 升级 GitHub Actions：upload-artifact v2 → v4

### v1.6.10 — 原版基线
- 来自上游 [eiyooooo/Easycontrol_For_Car](https://github.com/eiyooooo/Easycontrol_For_Car) 同步

---

## 📷 截图

<img src="pic/screenshot/main.webp" width="300px">
<img src="pic/screenshot/small.webp" width="300px">
<img src="pic/screenshot/small_landscape.webp" width="300px">
<img src="pic/screenshot/full.webp" width="300px">

## 🙏 致谢

- **原作者 [@eiyooooo](https://github.com/eiyooooo)**：开发并维护易控车机版
- **原仓库 [eiyooooo/Easycontrol_For_Car](https://github.com/eiyooooo/Easycontrol_For_Car)**：本 fork 的项目源头
- 所有为本项目和原项目贡献代码、反馈问题、捐赠支持的开发者与用户

## 💝 捐赠

- **本 fork 不接受任何捐赠**（不强制、不收集）
- 如果觉得好用，欢迎向**原作者**捐赠：<https://github.com/eiyooooo/Easycontrol_For_Car>

<img src="pic/other/wechat.webp" width="200px">
<img src="pic/other/alipay.webp" width="200px">

---

## 📄 许可证

本项目采用 **GPL-3.0** 许可证，详见 [LICENSE](./LICENSE)。

原版权属 [@eiyooooo](https://github.com/eiyooooo) 所有，本 fork 仅为功能增强。
