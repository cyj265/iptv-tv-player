# IPTV 播放器 (Android TV)

一个类似 TiviMate 的开源 IPTV 播放器，专为 **Android TV / 电视盒子** 设计，支持 **Android 7.0+**。

播放内核使用 **Media3 ExoPlayer**（Google 官方播放器内核），对 HLS 直播流兼容性最好，H.265/HEVC 高清源硬解。

> 以下界面为**示意预览**（非真机截图）。

| 全屏播放 | 频道列表（OK 键唤出） | 设置面板（菜单键唤出） |
| --- | --- | --- |
| ![全屏播放](docs/screenshot-play.jpg) | ![频道列表](docs/screenshot-channels.jpg) | ![设置面板](docs/screenshot-settings.jpg) |

## 功能

- 📺 **M3U / M3U8 / TXT 播放列表**：支持 URL 加载，也支持从本地文件导入
- 📡 **直播流链接直接播放**：无需播放列表，粘贴单个流地址即可看
- 🗂️ **频道分组 / 折叠 / 搜索 / 收藏**：电视遥控器友好，分组状态自动记忆
- 📅 **EPG 节目单**（XMLTV）：显示当前节目和下一个节目
- 📶 **自动识别流格式**：HLS (m3u8) / MPEG-TS / MP4 等
- 🖥️ **始终全屏播放**：播放画面不被频道栏遮挡，悬浮层按需唤出
- 🎨 **画面比例**：原始 / 16:9 / 4:3 / 缩放裁剪 / 拉伸全屏
- 💾 **自动恢复**：打开应用自动续播上次频道
- 📱 **Android 7.0+ 全支持**（minSdk 21），老盒子也能装

## 遥控器操作

| 按键 | 功能 |
| --- | --- |
| 上 / 下 方向键 | 切换上 / 下一个频道（全屏播放态） |
| OK / 确认键 | 唤出 / 收起频道列表（全屏播放态） |
| 菜单键 | 唤出 / 收起右侧设置面板 |
| CH+ / CH- | 切换上 / 下一个频道 |
| 播放 / 暂停键 | 播放 / 暂停 |
| 返回键 | 关闭已打开的悬浮面板 |

## 支持的源格式

| 类型 | 说明 |
| --- | --- |
| M3U / M3U8 | 标准播放列表（URL 或本地文件） |
| TXT | 每行 `频道名,流地址`（支持中文逗号、纯地址行） |
| 直播链接 | 单个 http/https 流地址直接播放 |
| EPG | XMLTV 格式节目单（可选） |

## 安装

1. 在 **Actions** 页面打开最新一次构建，下载 `iptv-player-apk` 工件中的 `iptv-player-vN.apk`
2. 将 APK 复制到电视（U 盘 / Send files to TV / adb install 均可）
3. 电视上开启“允许安装未知来源应用”，安装即可

> 打 `tag`（如 `v1.0.1`）时会自动发布 Release，也可以从 Releases 下载最新 APK。

## 在 GitHub 上构建

推送到 `main` 分支会自动触发 GitHub Actions 构建 APK（也可以在 Actions 页面手动 Run workflow）。

- 构建产物：`assembleRelease`，用 debug 密钥签名，可直接侧载安装（自用足够）
- 如需正式发布签名：在 `app/build.gradle.kts` 的 `release` 配置自己的 keystore

## 本地构建（可选）

需要 JDK 17 + Android SDK + Gradle 8.9：

```bash

gradle assembleRelease
# APK 输出: app/build/outputs/apk/release/app-release.apk
```

## 使用

1. 打开应用 → 按遥控器**菜单键**唤出右侧设置面板
2. 填入播放列表地址（M3U/TXT），或点**选择文件**从本地导入
3. 可选：填入 EPG 节目单地址
4. 返回播放界面，按 **OK 键**唤出频道列表选台，或直接**上下键**换台
5. 频道列表内按 **菜单键** 或面板内按钮进入设置；分组可折叠

## 技术栈

- Kotlin + AndroidX (AppCompat / RecyclerView / ViewBinding)
- **Media3 ExoPlayer**（HLS 专业支持，H.265/HEVC 硬解）
- minSdk 21 / targetSdk 34

## 致谢与许可

- 播放内核：**Media3 ExoPlayer**（Google 开源，Apache License 2.0）
  仓库：https://github.com/androidx/media
- 界面图标：**Material Design Icons**（Apache License 2.0）
- 解码兼容性方案参考：**lemonTV**（MIT License）
  仓库：https://github.com/jia070310/lemonTV
  参考内容：其播放器开启解码器回退（Decoder Fallback）以兼容 H.265 高清源的配置思路；本项目代码为独立编写，未复制其源码。
- 本播放器不内置任何直播源，仅播放用户提供的播放列表。

## 说明

- 本应用只播放你提供的播放列表，不内置任何直播源
- 部分运营商标记的源（如甘肃移动 39.134.x.x）通常**只能在对应运营商网络下播放**，与播放器无关
- H.265/HEVC 频道依赖电视硬件解码能力
