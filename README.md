![LumenTV-icon](readme_images/LumenTV-icon-svg.svg) 

# LumenTV Compose
[![Latest Release](https://img.shields.io/github/v/release/clevebitr/LumenTV-Compose?include_prereleases&style=flat-square)](https://github.com/clevebitr/LumenTV-Compose/releases/latest)
[![Stars](https://img.shields.io/github/stars/clevebitr/LumenTV-Compose?style=flat-square)](https://github.com/clevebitr/LumenTV-Compose/stargazers)
![License](https://img.shields.io/github/license/clevebitr/LumenTV-Compose?style=flat-square)
![Last Commit](https://img.shields.io/github/last-commit/clevebitr/LumenTV-Compose?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Windows_|_macOS_|_Linux-lightgrey?style=flat-square)
---
本项目基于 [TV-Multiplatform](https://github.com/Greatwallcorner/TV-Multiplatform)，[jetbrain/KMP](https://github.com/JetBrains/compose-multiplatform-desktop-template#readme)，[fonmi/TV](https://github.com/FongMi/TV)。名称中的 `Compose` 指的是 `compose-multiplatform` 所提供的多平台能力，但是本项目现阶段只使用桌面版本。使用方式和 `fonmi/TV` 类似，使用动态加载 `jar` 的方式获取需要展示的数据。需要结合为本项目定制的 `spider` 使用。
- 本项目使用的爬虫：[CatVodSpider](https://github.com/clevebitr/CatVodSpider)

## 目录

- [LumenTV Compose](#lumentv-compose)
    - [目录](#目录)
    - [核心功能](#核心功能)
        - [爬虫支持](#爬虫支持)
        - [混淆M3U8链接播放](#混淆m3u8链接播放)
        - [播放器支持](#播放器支持)
        - [广告过滤功能](#广告过滤功能)
        - [爬虫可用性检测](#爬虫可用性检测)
        - [DLNA投屏支持](#dlna投屏支持)
        - [平台支持](#平台支持)
    - [更新日志 (Changelog)](#更新日志-changelog)
    - [下载](#下载)
    - [自更新](#自更新)
    - [关于讨论群](#关于讨论群)
    - [声明](#声明)
    - [TODO](#todo)
    - [截图](#截图)
    - [引用](#引用)

## 核心功能

### 环境要求
- **操作系统**: Windows 10/11（Win7 SP1 尽力兼容，Windows 捆绑 [Python-Win7](https://github.com/Alex313031/Python-Win7) embed）, macOS (Intel / Apple Silicon), Linux
- **发行包**: 已捆绑 Java 运行时、Python 与 ffmpeg，一般无需再装 JDK / Python / ffmpeg；Windows 随包为 [Python-Win7](https://github.com/Alex313031/Python-Win7) embed + [Gyan ffmpeg 7.0](https://github.com/GyanD/codexffmpeg/releases/tag/7.0)（Win7 可用）
- **开发调试**: 推荐本机 Java 17+；未执行 `prepareBundledPython` / `prepareBundledFfmpeg` 时可回退系统 `python3` / `ffmpeg`
- **VLC 播放器**: 如需使用内部播放器，请安装 VLC（Windows 发行包已附带部分 VLC 原生库）

### 爬虫支持
- 支持 **Java JAR**、**JavaScript (.js)**、**Python (.py)** 三类爬虫
- JS 引擎基于 QuickJS（`wang.harlon.quickjs`）
- Python 爬虫随发行包内置（`requests` / `lxml` / `pycryptodome` / `certifi`）；开发时可 `./gradlew prepareBundledPython`
- 视频下载合并用 ffmpeg 随发行包内置；开发时可 `./gradlew prepareBundledFfmpeg`
- 网页解析 / WAF 场景使用内嵌 Chromium（JCEF），首次使用需下载原生包

### 混淆M3U8链接播放
本项目支持播放经过简单混淆的M3U8文件，如果遇到通过图床传输数据的M3U8文件时会切换到系统默认浏览器播放。
- **WebPlayer**：通过分析 M3U8 文件中的 链接后缀名 来判断是否为混淆链接，如果为png格式的混淆链接会通过浏览器播放。
- 注意：WebPlayer只支持M3U8格式的视频播放。


### 播放器支持
- **内部播放器**：如需使用内部播放器，请安装 VLC。如果无法自动找到 VLC，可以在输入框中输入 VLC 可执行文件的路径。
- **外部播放器**：需要调用外部的播放器软件，可以通过命令行传递视频网络地址的播放器都可以使用，支持的外部播放器有：
  1. VLC
  2. MPC - HC
  3. MPV 等
  4. PotPlayer

### 广告过滤功能
本项目基于 [M3U8 Filter Ad Script](https://github.com/ltxlong/M3U8-Filter-Ad-Script) 的重构kotlin实现的广告过滤功能，可有效拦截和过滤 M3U8 视频切片（插播）广告。该功能支持自动判断和暴力拆解两种模式，同时会在控制台打印过滤的行信息，不会误删正常视频内容。
- **自动判断模式**：通过分析 M3U8 文件中的 `EXTINF` 标签和 `ts` 序列号等信息，智能识别广告段并进行过滤。
- **暴力拆解模式**：在自动判断模式无法准确过滤时，可切换到暴力拆解模式，对可能的广告段进行强制过滤。
- **注意，开启广告过滤功能会影响m3u8文件解析的速度**

### 爬虫可用性检测
- 支持检测爬虫可用性，通过发送请求获取响应码来判断爬虫是否可用。
- 普通检测模式：通过发送请求获取响应码
- 搜索检测模式：通过发送请求获取响应码和响应头，判断是否为搜索结果页

### DLNA投屏支持
- 支持 DLNA投屏功能，同时支持内部/外部播放器播放
- 支持控制手机控制进度条/音量设置。(仅内部播放器支持)

### 内嵌浏览器（JCEF）
- Web 解析与需浏览器环境的爬虫使用 JCEF（内嵌 Chromium），首次使用会提示下载原生包；可在设置页管理。

### 平台支持
本项目支持 `Windows（含 Win7 尽力兼容） / Linux / macOS arm64 / macOS amd64`。GitHub Actions 会分别打包 macOS Apple Silicon 与 Intel 产物。Windows 随包 Python 使用 [Alex313031/Python-Win7](https://github.com/Alex313031/Python-Win7) embed（含 `api-ms-win-core-path`）。

## 更新日志 (Changelog)
您可以从以下链接查看更新历史日志：

[前往历史日志描述文件](https://github.com/clevebitr/LumenTV-Compose/blob/main/CHANGELOG.md)

## 下载

您可以从以下链接下载最新构建的版本：

[前往 Release 页面下载](https://github.com/clevebitr/LumenTV-Compose/releases)

## 自更新
本项目支持自动更新，您可以通过以下方式进行更新：
- 运行 `LumenTV-Compose.exe` 自动检查更新
- 通过设置页手动检查更新
### ❗强烈建议阅读自更新说明文档：
[查看自更新说明文档](https://github.com/clevebitr/LumenTV-Compose/blob/main/updater/README.md)

## 关于讨论群
本项目无讨论群！不要在原项目 tg 群里提及有关该项目问题！

## 声明
- 本项目使用的广告过滤功能基于 [M3U8 Filter Ad Script](https://github.com/ltxlong/M3U8-Filter-Ad-Script)，遵循其开源协议。
- 本项目仅用于学习和技术交流，请勿用于商业用途。

## TODO
- [x] Decompose
- [x] 关于页
- [x] 优化日志设置 debug log 设置读取
- [x] 优化从搜索页进入详情页的时候使用的搜索结果集
- [x] 优化搜索调用次数,搜索页搜索时，默认搜索两个站源，如果为空则继续搜索，如过用户想加载更多，手动点击加载更多的按钮
- [x] vlcj
- [x] 支持文件夹浏览
- [X] WebPlayer
- [X] 明/暗色主题实现
- [X] 支持广告过滤
- [X] 自更新
- [X] 添加playewright爬虫支持
- [x] 下载 aria2
- [x] JS / Python 爬虫支持
- [x] 直播基础功能（M3U/TXT/JSON 解析）
- [x] Web 遥控面板（搜索/推送/弹幕/设置/本地文件）
- [x] 壁纸配置 / 弹幕系统 / DLNA 投出
## 截图
### 首页
![](readme_images/home.png)
### 站源选择
![](readme_images/source.png)
### 历史记录
![](readme_images/history.png)
### 搜索
![](readme_images/search.png)
### 搜索结果页
![](readme_images/search_result.png)
## 详情页
### 内部播放器
![](readme_images/internalPlayer.png)
### 外部播放器
![](readme_images/externalPlayer.png)
### Web播放器
![](readme_images/dialog.png)
![](readme_images/webplayer.png)
### 设置页
![](readme_images/settings_1.png)
![](readme_images/settings_2.png)
![](readme_images/settings_3.png)
![](readme_images/settings_4.png)
![](readme_images/settings_5.png)
![](readme_images/settings_6.png)
## 关于页
![](readme_images/settings_7.png)

---
## 引用
- player: https://github.com/numq/jetpack-compose-desktop-media-player
- animeko: https://github.com/open-ani/animeko?tab=readme-ov-file
- 广告过滤脚本: https://github.com/ltxlong/M3U8-Filter-Ad-Script
- FPS监控: https://github.com/succlz123/AcFun-Client-Multiplatform
