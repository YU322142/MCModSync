# MCModSync

MCModSync 是一个 Fabric 客户端启动前同步工具。它把 Mod 分成“必须模组”和“推荐模组”，使用 MD5 与 SHA256 双校验，并根据 Windows、Mac、Linux、手机端的兼容性决定推荐组合。清单、客户端配置和升级入口都可以公开审计；本仓库不包含任何生产站点、令牌、账号或私有路径。

> [!WARNING]
> **AI 辅助生成代码警示：** 本项目包含 AI 辅助生成或修改的代码与文档。AI 输出不是安全审计，也不保证没有逻辑错误、兼容性问题或未知风险。部署前请自行审阅源码、扫描依赖、在隔离实例测试并保留可恢复备份。只发布你有权分发且已经验证的 JAR。对外公开日志前请删除签名 URL、内部域名和本机路径。

> [!IMPORTANT]
> 所有示例 URL 都使用 `example.invalid`。它们不可访问，必须替换为你自己控制的 HTTP/HTTPS 直链。不要把真实的 `modsync.properties` 提交到公共仓库。

## 版本和文件角色

当前发布版本为 `1.8.3`，支持 Java 21、Fabric Loader 0.16+ 和 Minecraft 1.21.11+。源码同时保留 v1/v2/v3 读取兼容性。

服务器目录应长期保留以下文件：

```text
release/
├─ mods.txt                  # 永久 v2 旧版升级入口，只给 1.6.x/1.7 及更旧客户端
├─ mods-v4.txt               # 正式 v4 清单，新客户端从这里读取
├─ MCModSync-Config.jar      # 固定名配置引导 JAR，必须随两个清单一起发布
├─ MCModSync-1.8.3.jar       # 当前同步器
├─ fabric-api.jar
├─ required-mod.jar
└─ recommended-mod.jar
```

`mods.txt` 和 `mods-v4.txt` 应与其中列出的 JAR 放在同一 HTTP 目录。清单中的文件名按清单 URL 的目录解析，所以发布时先上传 JAR，再上传清单。`mods.txt` 是永久旧版入口，不要把它改成 v4，也不要删除它，否则以后仍使用旧版的客户端无法自动升级。

## 运行规则

### 必须模组 / Required

- 所有平台都必须存在。
- 缺失、文件名不匹配、MD5 不符或 SHA256 不符时自动下载或修复。
- 清单不可用、下载失败或双哈希失败时阻止游戏主类启动。
- 阻止启动、需要重启更新、配置更新后的重启都以退出码 `0` 正常结束，让启动器显示“正常退出”，而不是崩溃。

### 推荐模组 / Recommended

电脑端第一次遇到清单，或 `catalog-version` 发生变化时，会在启动前打开选择窗口：

- 所有当前平台兼容项默认勾选；云端标记为当前平台不兼容的项默认不勾选且禁止勾选。
- 每行“必须 / Required”和“推荐 / Recommended”只能选择一个。
- 提供“一键取消所有推荐模组”，全部不选也允许启动。
- 点击取消或关闭窗口等同于按当前勾选继续，不会强制选择全部。
- 已安装但被取消的推荐 JAR 会移动到 `.modsync/backups`，不会直接删除。
- 选择保存在 `.modsync/recommended-selection.properties`，同一清单版本以后直接复用。

手机端不会显示窗口，会自动下载当前平台兼容的全部推荐模组。每个 `catalog-version` 只自动处理一次；用户之后删除或破坏推荐 JAR 时不二次下载，日志会给出手动下载 URL、SHA256 和目标路径。清单版本更新会在日志中提示并开启新批次的一次自动处理。推荐模组缺失允许继续启动，必须模组仍严格阻止启动。

只有以下启动器识别为手机端：`PojavLauncher`、`MCinaBox`、`FCL`、`Zalith Launcher 2`。其他启动器均按电脑端处理。

## 第一次发布

### 1. 准备发布目录

发布目录的父目录被视为游戏根目录。例如 `D:\Release\1.21.11-fabric\mods` 的配置模板必须是 `D:\Release\1.21.11-fabric\modsync.properties`。把当前 `MCModSync-1.8.3.jar` 放进 `mods` 目录；发布器会把它作为必须模组写入 v4/v2 以支持后续自更新，并生成固定名 `MCModSync-Config.jar`。

复制 [`modsync.properties.example`](modsync.properties.example) 到游戏根目录并改名为 `modsync.properties`，至少修改：

```properties
manifest=https://files.example.com/minecraft/mods-v4.txt
syncResourcePacks=false
syncServerList=false
strict=true
requireManifest=true
```

`manifest` 必须以 `/mods-v4.txt` 结尾。`mobileManifest`、资源包和服务器列表是可选的；启用对应同步时必须提供有效清单 URL。这个文件既是客户端配置，也是发布器的配置模板。发布器只读取其中的服务器管理项，不会把本地语言、超时、重试和文件大小限制写入远程文件。

### 2. 用图形发布器编辑

运行 `MCModSync-1.8.3.jar`，选择已经测试完成的 `mods` 目录，然后打开必须/推荐清单编辑器。

1. 如需接着上次发布的清单修改，勾选“扫描后选择上次清单”。工具先扫描当前 `mods`，再打开你选择的 v3/v4 清单；当前文件夹决定最终条目集合，已删除 JAR 不会残留。
2. 每行只能勾选一个类型。Fabric API、前置库和服务器要求的内容选择 `required`；可选性能、地图、光影等选择 `recommended`。
3. 推荐项填写“不兼容的平台”。这是排除列表；不勾选表示四个平台都兼容。可选值为 `windows`、`mac`、`linux`、`mobile`。
4. 编辑显示名称、版本、中文描述和 English description。JAR 的 `fabric.mod.json` 通常只有一个描述字段：含中文时先填中文列，否则填英文列；发布器不会自动翻译。继续使用上次清单可以保留人工填写的双语内容。
5. 更新 `catalog-version`。新增/删除推荐项、类型、平台兼容性、默认组合或说明发生变化时都应增加版本号。
6. 生成清单。工具会计算 MD5/SHA256、写入 `mods-v4.txt`，写入永久兼容入口 `mods.txt`，并生成或更新 `MCModSync-Config.jar`。

表格底部的“所选设为必须模组”和“所选设为推荐模组”支持批量操作；没有选择行时会作用于全部条目。`required`/`recommended` 互斥约束在界面和清单解析阶段都会检查。

### 3. 命令行发布

命令行扫描并生成 v4 清单，所有扫描到的条目默认为必须模组；需要推荐分类、双语描述和平台排除列表时请使用图形编辑器：

```powershell
java -jar MCModSync-1.8.3.jar "D:\Release\1.21.11-fabric\mods"
```

默认输出为 `mods-v4.txt`，并在 `mods` 目录旁生成永久 `mods.txt`。也可以指定 v4 输出路径：

```powershell
java -jar MCModSync-1.8.3.jar "D:\Release\1.21.11-fabric\mods" "D:\Publish\mods-v4.txt"
```

## 从 1.6.x/1.7 无缝升级

旧版客户端只理解 v1/v2，不理解 v4。当前方案不需要更换旧客户端里的 URL：旧 URL 永久指向 `mods.txt`，v2 清单会把当前同步器和配置引导 JAR 当作必须模组发下去。

发布器已生成 `mods.txt` 时，线上目录保持以下内容并让旧 URL 指向 `mods.txt`：

```text
mods.txt
mods-v4.txt
MCModSync-Config.jar
MCModSync-1.8.3.jar
其余所有 Mod JAR
```

旧客户端的启动过程：

1. 读取原来的 v2 `mods.txt`。
2. 下载并校验 `MCModSync-1.8.3.jar`，按 Fabric Mod ID `mcmodsync` 自动移出旧版本并安装新版本。
3. 下载固定名 `MCModSync-Config.jar`。
4. 正常退出，启动器显示正常退出。

下一次启动时，1.8.3 会从 `mods/MCModSync-Config.jar` 读取服务器管理配置，自动创建或更新游戏根目录的 `modsync.properties`，保留本地专属设置，然后读取 `mods-v4.txt`。配置已经存在且无其他更新时可以直接继续进入游戏；配置或 Mod 需要更新时会正常退出并提示再次启动。

因此不要把过渡内容临时覆盖到 v4 文件，也不要把 v2 清单另存为一个旧客户端不知道的 URL。`mods.txt` 可以永久保留，供以后仍在使用 1.6.x/1.7 的客户端升级。

也可以只生成永久 v2 入口：

```powershell
java -jar MCModSync-1.8.3.jar --upgrade-v2 "D:\Release\1.21.11-fabric\mods"
```

这个命令默认写入 `D:\Release\1.21.11-fabric\mods\mods.txt`，仍会读取配置模板并确保配置引导 JAR 在清单中。发布前必须把 v4、v2、配置引导 JAR 和所有 Mod 一起上传。

## 清单格式和配置管理

典型 v4 文件包含：

```text
# mcmod-sync-v4
# catalog-version=2026-07-30-01
# client-config.manifest=https://files.example.com/minecraft/mods-v4.txt
# client-config.mobileManifest=https://files.example.com/minecraft/mobile-mods-v4.txt
# client-config.syncResourcePacks=false
# client-config.syncServerList=false
# client-config.strict=true
# client-config.requireManifest=true
# SHA256<TAB>MD5<TAB>Mod ID<TAB>文件名<TAB>类型<TAB>不兼容平台<TAB>名称<TAB>版本<TAB>中文描述<TAB>English description
<sha256>\t<md5>\tsodium\tsodium.jar\trecommended\tmobile\tSodium\t1.0\t渲染优化\tRendering optimization
```

只有以下服务器管理项允许出现在 `client-config.*` 中：`manifest`、`mobileManifest`、`syncResourcePacks`、`resourcePackManifest`、`mobileResourcePackManifest`、`syncServerList`、`serverListManifest`、`strict`、`requireManifest`。`requireManifest=false` 会被拒绝，未知项也会让清单失效。

文件大小限制不属于远程配置：客户端未填写 `maxFileBytes` 时默认不主动限制下载文件大小；只有玩家/整合包在本地 `modsync.properties` 中显式填写时才启用该限制。`maxFileBytes`、`language`、超时和重试参数不会写进 v4 清单或配置引导 JAR，也不会被服务器覆盖。

远程配置更新时，新客户端会原子更新 `modsync.properties`，保留全部本地专属键，并在需要时正常退出一次让新配置生效。配置引导 JAR 使用固定文件名和固定资源路径，客户端不会扫描或信任其他普通 Mod JAR 中的配置资源。

## 玩家配置、语言和卸载

1. 把 `MCModSync-1.8.3.jar` 放入实例 `mods` 目录。
2. 将 [`modsync.properties.example`](modsync.properties.example) 复制到游戏根目录并改名为 `modsync.properties`。
3. 修改自己的 v4 直链。语言可填 `auto`、`zh_cn` 或 `en_us`；也可用 `-Dmodsync.language=en_us` 临时覆盖。
4. 不使用资源包或服务器列表时，把 `syncResourcePacks`/`syncServerList` 设为 `false`。

卸载时完全退出游戏和所有 Java 进程：

1. 从实例 `mods` 删除 `MCModSync-1.8.3.jar` 和 `MCModSync-Config.jar`。
2. 删除游戏根目录 `modsync.properties`。
3. 如果使用过 `-javaagent:`，从启动器 JVM 参数删除对应参数。
4. 需要恢复被取消的推荐 Mod 时，先从 `.modsync/backups/<批次>/` 手动移回并检查哈希，再决定是否删除 `.modsync`。卸载不会自动删除或恢复其他 Mod。

## 可选资源包和服务器列表

```powershell
java -jar MCModSync-1.8.3.jar --resourcepack "D:\Publish\server-pack.zip"
java -jar MCModSync-1.8.3.jar --serverlist "D:\Publish\servers.dat"
```

分别生成资源包和服务器列表清单。将清单和对应文件放到直链目录，并在本地配置中启用对应开关。Mod v4 使用 MD5+SHA256；资源包和服务器列表继续使用各自格式。

## 排错和状态文件

- `.modsync/recommended-selection.properties`：电脑端选择和手机端一次处理状态。
- `.modsync/server-manifest.txt`：最近一次远程 Mod 清单缓存。
- `.modsync/backups/`：被替换或取消的文件。
- `.modsync/progress.log`、`.modsync/helper.log`、`.modsync/ui-status.txt`：下载、辅助更新和无弹窗状态。
- `logs/latest.log`：Fabric/游戏日志。

清单版本更新时日志会输出旧版本和新版本。手机端同版本删除推荐 Mod 后只记录手动恢复方式；电脑端重新选择即可。启动阻止应看到 `STARTUP_BLOCKED`，但退出码为 `0`，且游戏主类不会运行。

发布顺序建议：先上传所有 JAR，确认 URL 返回原始文件，再上传两个清单；CDN 有缓存时先处理缓存失效，避免清单和 JAR 跨版本混用。

## 构建和测试

```powershell
./build.ps1
```

脚本会编译主类和测试、运行完整测试、构建 `build/dist/MCModSync-1.8.3.jar`，并执行正常退出、便携模式、资源同步和清单兼容性检查。要验证真实旧版升级：

```powershell
$env:MCMODSYNC_LEGACY_JAR = 'D:\Legacy\MCModSync-1.6.10.jar'
./build.ps1
```

1.7 验证时额外设置 `MCMODSYNC_LEGACY_ENTRYPOINT=io.github.mcmodsync.FabricPreLaunchEntrypoint`。构建输出会复制到仓库上级 `outputs` 目录。发布前请检查 `git diff` 和敏感信息扫描。

## 许可证

本项目采用 [MIT License](LICENSE)。
