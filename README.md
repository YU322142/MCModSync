# MCModSync

MCModSync 1.8.1 是一个 Fabric 客户端启动前同步工具。服主可以在同一份 v4 Mod 清单中区分“必须模组”和“推荐模组”，设置推荐模组的不兼容平台，为每个 Mod 提供中文和英文描述，并让客户端下载后同时校验 MD5 与 SHA256。资源包和服务器列表仍可独立同步。

> [!WARNING]
> **AI 辅助生成代码警示：** 本项目包含 AI 辅助生成或修改的代码与文档。AI 生成内容可能存在逻辑错误、兼容性问题或未知安全风险，不能视为安全审计结论。公开部署前请自行审阅代码、扫描依赖、在隔离测试实例完整验证并保留可恢复备份。使用者自行负责云端清单、下载文件和托管服务器的可信性与安全性。

> [!IMPORTANT]
> 公开源码不包含生产站点、凭据、令牌、私有路径或个人信息。所有内置 URL 都是不可访问的 `example.invalid` 占位符，必须换成你自己的直链。

## 核心规则

### 必须模组

- Windows、Mac、Linux 和手机端都必须安装。
- 启动前检查本地文件；缺失、MD5 不符或 SHA256 不符时自动下载/修复。
- 必须清单不可用、下载失败或双哈希校验失败时阻止进入游戏。
- 阻止启动和“更新完成后需要重启”均以退出码 `0` 正常结束，让启动器显示正常退出而不是游戏崩溃。

### 推荐模组（电脑端）

- 首次遇到推荐清单或 `catalog-version` 变化时，在启动前显示选择窗口。
- 所有兼容当前平台的推荐模组默认勾选。
- 云端标记为不兼容当前平台的条目自动取消勾选并禁止勾选。
- 支持“一键取消所有推荐模组”；一个都不选也允许启动。
- 关闭选择窗口等同于“按当前勾选继续”，不会取消启动流程。
- 选择保存在 `.modsync/recommended-selection.properties`；同一清单版本以后直接复用。
- 取消已安装的推荐模组时，JAR 会安全移出 `mods` 并保存到 `.modsync/backups`，不会永久删除。
- 清单版本变化会在日志输出旧版本和新版本，然后重新显示选择窗口。

### 推荐模组（手机端）

- 不显示选择窗口，自动选择当前手机端兼容的全部推荐模组。
- 同一 `catalog-version` 只自动处理一次。
- 用户之后删除或损坏推荐模组时，不会在同一清单版本内二次下载；日志会输出名称、文件名、手动下载直链、SHA256 和应放入的 `mods` 路径。
- `catalog-version` 更新时日志会提示版本变化，并把它视为新的发布批次，获得一次新的自动处理机会。
- 推荐模组缺失不会阻止启动；必须模组仍严格检查。

## 支持的平台

推荐模组兼容性使用四个平台：`windows`、`mac`、`linux`、`mobile`。

只有以下启动器被识别为手机端：

- PojavLauncher
- MCinaBox
- FCL（Fold Craft Launcher）
- Zalith Launcher 2

其他启动器即使运行在 Android、Cacio AWT 或伪装的 Linux 环境中，也按电脑端处理。

## 中文与 English

- v4 清单为每个 Mod 分别保存“中文描述”和“English description”。
- 推荐选择窗口按当前语言只显示对应描述；对应语言为空时自动回退另一种语言，不会显示空白。
- `language=auto` 先读取 Minecraft `options.txt` 中的语言，其次跟随系统语言。
- 可在 `modsync.properties` 中用 `language=zh_cn` 或 `language=en_us` 强制指定，也可用 JVM 参数 `-Dmodsync.language=en_us` 临时覆盖。
- 推荐选择窗口、清单发布工具和主要同步提示支持中文与英文。发布编辑表头同时标注两种语言，便于共同维护双语内容。

## 环境要求

- Java 21 或更新版本
- Fabric Loader 0.16 或更新版本
- Minecraft 1.21.11 或更新版本
- 玩家设备能够直接访问你的 HTTP/HTTPS 文件地址

只分发你有权分发的 Mod 和资源。发布前必须在备用实例验证依赖和兼容性。

## 一、生成必须/推荐 Mod 清单

### 图形界面（推荐）

1. 运行或双击 `MCModSync-1.8.1.jar`。
2. 点击“选择 mods 目录”，选择已经测试完成的发布目录。要兼容 1.6.x/1.7 升级或让 MCModSync 自身更新，这个目录必须包含 `MCModSync-1.8.1.jar`。
3. 点击“编辑必须/推荐模组并生成清单”。
4. 工具读取每个 JAR 的 `fabric.mod.json`，并计算 MD5、SHA256。
5. 每行通过“必须 / Required”和“推荐 / Recommended”两个复选框选择类型。两者强制二选一：勾选其中一个会自动取消另一个，不能同时勾选，也不能全部不选。
6. 对推荐模组勾选“不兼容的平台”。这是排除列表，不勾选表示所有平台兼容。
7. 核对显示名称和版本，并分别填写“中文描述”和“English description”。这些内容会按玩家语言显示在电脑端的启动前选择窗口。
8. 设置新的“推荐清单版本”。每次改变推荐列表、默认组合、兼容平台、名称、版本或描述时都要增加它。
9. 点击“生成 v4 清单”，得到正式使用的 `mods.txt`。目录中包含合格的当前 MCModSync JAR 时，还会同时生成只供旧版升级的 `mods-upgrade-v2.txt`。

表格中的“所选设为必须模组”“所选设为推荐模组”可以批量修改；没有选择行时会应用到全部条目。

### 命令行

```powershell
java -jar MCModSync-1.8.1.jar "D:\Publish\mods" "D:\Publish\mods.txt"
```

命令行批量生成时所有条目默认是 `required`。需要配置推荐类型、显示信息和不兼容平台时，应使用图形界面编辑器。

### v4 清单格式

一般不要手写清单。排错时可参考：

```text
# mcmod-sync-v4
# catalog-version=2026-07-30-01
# SHA256\tMD5\tMod ID\t文件名\t类型\t不兼容平台\t名称\t版本\t中文描述\tEnglish description
<SHA256>\t<MD5>\tsodium\tsodium.jar\trequired\t-\tSodium\t1.0\t渲染优化\tRendering optimization
<SHA256>\t<MD5>\tiris\tiris.jar\trecommended\tmobile,mac\tIris\t1.0\t光影支持\tShader support
```

旧 v1/v2/v3 清单仍可读取。v1/v2 没有推荐分类，条目按必须模组处理；v3 的单描述会自动迁移到中文或英文并在另一语言下回退显示。新发布应使用 v4。

### 从 1.6.x/1.7 安全升级

1.6.x 和 1.7 不认识 v4，必须先经过 v2 过渡清单。发布工具会验证扫描目录中存在 Fabric Mod ID 为 `mcmodsync`、版本不低于 1.8.0 的同步器，防止生成无法完成升级的过渡清单。命令行也可以单独生成：

```powershell
java -jar MCModSync-1.8.1.jar --upgrade-v2 "D:\Publish\mods" "D:\Publish\mods-upgrade-v2.txt"
```

线上切换顺序：

1. 先上传 `MCModSync-1.8.1.jar` 和过渡清单列出的全部 JAR。
2. 暂时把线上配置的 `mods.txt` 内容替换为 `mods-upgrade-v2.txt` 的内容。不能只把它作为另一个文件上传，因为旧客户端只会请求已经配置的清单 URL。
3. 启动旧客户端并确认日志显示需要重启；第二次启动应显示 MCModSync 1.8.1。旧 JAR 会按同一个 Mod ID 自动移出并替换，进程以退出码 `0` 正常结束。
4. 确认仍需支持的 1.6.x/1.7 客户端已经升级后，再把线上 `mods.txt` 替换为正式 v4 `mods.txt`。
5. 再次启动，电脑端按 v4 显示推荐模组选择；手机端按 v4 自动处理推荐模组。

过渡 v2 没有“推荐”类型，因此过渡期间所有条目都会被旧客户端当作必须模组；切到 v4 后会立即按最终选择安全移出不需要的推荐模组。不要让旧同步器直接读取 v4，否则它会因格式未知而安全阻止启动。

此升级链路已用用户提供的真实 `MCModSync-1.6.10.jar` 和从本仓库 1.7.0 源码重建的真实 JAR 完成端到端替换测试；自动化测试还会验证 v2 结构、Mod ID 替换及正常退出行为。

## 二、上传文件

把 `mods.txt` 和其中列出的所有 JAR 放在同一公开目录：

```text
https://files.example.com/minecraft/
├─ mods.txt
├─ fabric-api.jar
├─ sodium.jar
└─ iris.jar
```

客户端下载地址由 `mods.txt` 的目录加文件名组成。链接必须直接返回文件内容，不能要求登录、验证码，也不能返回网页预览。上传更新时应先上传 JAR，确认可下载后再上传新清单，避免客户端读到尚未完整发布的版本。

## 三、配置玩家客户端

1. 将 `MCModSync-1.8.1.jar` 放入实例的 `mods` 目录。
2. 把 [`modsync.properties.example`](modsync.properties.example) 复制到游戏根目录，即包含 `mods` 的目录。
3. 将复制件改名为 `modsync.properties`。
4. 把占位 URL 改成你的真实地址：

```properties
manifest=https://files.example.com/minecraft/mods.txt
language=auto

syncResourcePacks=false
syncServerList=false

strict=true
requireManifest=true
```

不使用资源包或服务器列表时，必须把对应开关设为 `false`。真实 `modsync.properties` 可能包含内部域名或签名 URL，已经被 `.gitignore` 忽略，不要提交到公共仓库。

## 卸载或停用

1. 完全退出 Minecraft 和启动器中的 Java 进程。
2. 从实例的 `mods` 目录移除 `MCModSync-1.8.1.jar`。如果曾使用 `-javaagent:` 方式安装，还必须从启动器的 JVM 参数中删除对应的 `-javaagent:<路径>`。
3. 删除或移走游戏根目录的 `modsync.properties`。
4. 先检查 `.modsync/backups` 中是否有需要恢复的推荐模组或旧版本文件；确认不再需要后，才删除整个 `.modsync` 目录。该目录还包含推荐选择状态和日志，提前删除会让仍在运行的 MCModSync 把当前清单当作首次处理。

卸载 MCModSync 不会自动删除已同步的其他 Mod，也不会自动把备份放回 `mods`。需要恢复某个文件时，应在游戏完全退出后从 `.modsync/backups/<批次>/` 手动移回，并自行确认依赖兼容。整合包维护者还应从分发包和安装脚本中移除 MCModSync，避免下一次更新再次安装。

## 四、资源包和服务器列表（可选）

```powershell
java -jar MCModSync-1.8.1.jar --resourcepack "D:\Publish\server-pack.zip"
java -jar MCModSync-1.8.1.jar --serverlist "D:\Publish\servers.dat"
```

分别生成 `resourcepacks.txt`、`serverlist.txt`。将清单和对应的 ZIP/`servers.dat` 放在同一云端目录，并在配置中启用：

```properties
syncResourcePacks=true
resourcePackManifest=https://files.example.com/minecraft/resourcepacks.txt

syncServerList=true
serverListManifest=https://files.example.com/minecraft/serverlist.txt
```

SHA256 双校验应用于 Mod v3/v4 清单；资源包和服务器列表仍使用各自的 MD5 清单格式。

## 五、首次测试

1. 备份整个实例，特别是 `mods`、`resourcepacks`、`servers.dat` 和 `.modsync`。
2. 用全新电脑端实例启动，确认推荐选择窗口、全选和一键取消可用。
3. 取消一个已经安装的推荐模组，确认它被移动到 `.modsync/backups`。
4. 不选择任何推荐模组，确认仍能启动。
5. 用四种受支持手机启动器中的实际目标启动器测试一次；删除一个已自动安装的推荐模组，再启动并确认只记录手动安装提示、不二次下载。
6. 修改 `catalog-version`，确认日志出现 `旧版本 -> 新版本`；电脑端应重新询问，手机端应执行新批次的一次自动处理。
7. 破坏一个必须模组或把必须清单 URL 改成无效地址，确认游戏不会进入，并且启动器显示正常退出而不是崩溃。

## 配置项

| 配置项 | 作用 | 建议 |
| --- | --- | --- |
| `manifest` | Mod v4 清单 URL | 必填 |
| `mobileManifest` | 手机启动器使用的另一份 Mod 清单 | 可选；通常使用同一份即可 |
| `language` | `auto`、`zh_cn` 或 `en_us` | 默认 `auto` |
| `syncResourcePacks` | 是否同步资源包 | 不使用时设为 `false` |
| `resourcePackManifest` | 资源包清单 URL | 启用时必填 |
| `mobileResourcePackManifest` | 手机端资源包清单 URL | 可选 |
| `syncServerList` | 是否同步服务器列表 | 不使用时设为 `false` |
| `serverListManifest` | 服务器列表清单 URL | 启用时必填 |
| `strict` | 服务器移除旧管理 Mod 时执行严格处理 | 推荐 `true` |
| `requireManifest` | 清单不可用时阻止启动 | 安全策略固定要求为 `true` |
| `connectTimeoutSeconds` | 建连超时秒数 | 默认 `15` |
| `requestTimeoutSeconds` | 请求总超时秒数 | 默认 `300` |
| `maxFileBytes` | 单文件最大字节数 | 默认 2 GiB |
| `fileOperationRetries` | 文件占用时的重试次数 | 默认 `12` |

## 状态、备份和日志

- 推荐选择：`.modsync/recommended-selection.properties`
- 同步历史：`.modsync/server-manifest.txt`
- 自动备份：`.modsync/backups/`
- 无弹窗环境状态：`.modsync/ui-status.txt`
- 下载进度：`.modsync/progress.log`
- 退出后更新日志：`.modsync/helper.log`
- Minecraft 日志：`logs/latest.log`

不要直接编辑状态文件。需要在测试客户端完全重置推荐选择时，应先备份 `.modsync`，关闭所有游戏/Java 进程，再移走 `recommended-selection.properties`。

## 常见问题

**清单版本更新但电脑端没有弹窗**：确认 `catalog-version` 确实变化，并检查是否被无弹窗/headless 参数禁用了 Swing。日志会记录平台、版本变化和回退行为。

**手机端删除推荐模组后没有自动恢复**：这是设计行为。同一清单版本只自动处理一次，请按日志中的直链和 SHA256 手动安装；或者发布新的 `catalog-version`。

**取消推荐模组后找不到文件**：查看 `.modsync/backups`。取消操作是安全移出，不是永久删除。

**启动被阻止**：检查必须清单 URL、HTTPS 证书、直链权限、文件名和双哈希。错误会记录在启动器日志；进程仍以正常退出码结束。

**每次都重新下载**：通常是云端文件内容与清单哈希不同，或 CDN 混用了新旧版本。重新生成清单并清理 CDN 缓存。

完整运维流程见 [中文使用指南](docs/中文使用指南.md)。

## 从源码构建

```powershell
./build.ps1
```

脚本会编译、运行全部测试并输出 `build/dist/MCModSync-1.8.1.jar`。如需额外运行真实旧版升级冒烟测试，可设置 `MCMODSYNC_LEGACY_JAR`；1.6.x 默认入口点会自动使用，验证 1.7 时可再设置 `MCMODSYNC_LEGACY_ENTRYPOINT=io.github.mcmodsync.FabricPreLaunchEntrypoint`。

## 许可证

本项目采用 [MIT License](LICENSE)。
