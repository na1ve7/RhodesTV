# 罗德岛TV (RhodesTV)

家庭电视用的 IPTV 直播客户端：遥控器友好的大屏界面，支持频道分组/收藏、数字键直选、手机远程管理，以及**应用内一键更新**。

## 功能

- **直播播放**：ExoPlayer(Media3) 播放 M3U/HLS，支持 http/https/udp 代理、EPG 不依赖
- **频道组织**：按频道名**自动重分类为 12 个稳定大类**(央视频道/卫视频道/地方频道/影视综艺/纪录人文/少儿动画/音乐戏曲/体育频道/新闻财经/港澳台频道/国际频道/其他频道)，上游混乱的 `group-title` 只作为兜底提示；任意调整分组顺序、频道置顶/隐藏，收藏夹独立入口
- **多线路合并与失败即切**：同名频道(含画质后缀差异)自动合并为多条线路，起播超时/播放出错自动切下一条，坏线路降权记忆 + 全线路失败自动重试；面板打开时 `左/右` 手动切线路并记住选择
- **遥控器操作**：方向键浏览、数字键快速换台、`P+/P-`、长按呼出菜单、`Menu` 键进设置
- **收藏/最近**：一键收藏，最近观看记录
- **手机远程管理**：内置 HTTP 服务(默认 8765 端口)，手机浏览器打开 `http://电视IP:8765` 即可
  - 上传/切换 M3U 订阅源、编辑分组与频道、拖动排序、搜索过滤
  - `GET /api/status` 设备与播放状态、`GET /api/channels`、`GET /api/groups`、`GET /api/fav`
  - `GET /api/update` 远程查看是否有新版本
- **应用内一键更新(OTA)**：设置页点「检查更新」→ 自动从多个镜像拉取 `update.json` → 比对 `versionCode` → 下载 APK 并校验 SHA-256 → 调用系统安装器覆盖安装；也可开关「启动时自动检查」(默认 6 小时节流)

## 构建

需要 JDK 17 + Android SDK(compileSdk 34)。项目自带 Gradle 配置，未包含 wrapper，请用 Gradle 8.7：

```bash
JAVA_HOME=<jdk17> gradle :app:assembleRelease     # 产物 app/build/outputs/apk/release/app-release.apk
JAVA_HOME=<jdk17> gradle :app:testReleaseUnitTest # 单元测试(Robolectric/纯 JVM 共 22 项)
```

发布版签名密钥**不入库**，请通过环境变量提供(未提供时回退到 debug 签名)：

```bash
RHODES_STORE_FILE=/path/rhodes.keystore RHODES_STORE_PASSWORD=*** RHODES_KEY_ALIAS=rhodes RHODES_KEY_PASSWORD=*** gradle :app:assembleRelease
```

## 一键更新(OTA)原理

`dist/update.json`：

```json
{
  "versionCode": 3,
  "versionName": "1.2",
  "apkUrl": "https://cdn.jsdelivr.net/gh/na1ve7/RhodesTV@main/dist/RhodesTV.apk",
  "mirrors": ["https://raw.githack.com/na1ve7/RhodesTV/main/dist/RhodesTV.apk",
              "https://raw.githubusercontent.com/na1ve7/RhodesTV/main/dist/RhodesTV.apk"],
  "sha256": "<apk 的 SHA-256>",
  "note": "更新说明"
}
```

客户端按 `update.json` 的地址列表依次尝试(国内优先 jsDelivr)，成功后比对 `versionCode`，大于本机才提示更新。客户端内置的清单地址与镜像可在设置页「更新源」中修改(`Prefs.KEY_UPDATE_URLS`)，自定义源可放自建服务器。

> 发布新版本的流程：改 `versionCode`/`versionName` → 构建 APK → 覆盖 `dist/RhodesTV.apk` → 更新 `dist/update.json`(版本号 + 新的 sha256) → push。所有已装 App 即可一键升级。

## 目录

```
app/src/main/java/com/rhodes/tv/
  MainActivity.kt        主界面(双栏导航/频道卡、遥控器按键、面板动画)
  PlayerController.kt    播放控制/缓冲策略、失败即切与线路记忆
  GroupRules.kt          频道名 → 12 大类重分类与同名归一化(词表唯一真源)
  M3uParser.kt           M3U 解析 + 同名合并 + 去重
  Store.kt               数据存储(Prefs/channelStore/线路记忆/list)
  ConfigServer.kt        手机远程管理 HTTP 服务(8765)
  SettingsActivity.kt    设置页(订阅源、更新、端口、偏好)
  TvState.kt / Updater.kt / UpdateWorker.kt / XmltvParser.kt
dist/                    发布产物(update.json + APK)
```

## 免责声明

本项目仅作为播放器前端，不含任何频道源；请自行准备合法的 M3U 订阅源。
