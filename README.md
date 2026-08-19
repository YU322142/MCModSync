# MCSync 2.0

MCSync 是面向 Minecraft 整合包的启动前 OTA 工具。它在正常模组初始化前检查发布清单，下载并校验变更，然后以事务方式更新客户端。

当前正式版本：**2.0.0**

运行环境：**Java 21**

当前清单：**mods-v5.json**
技术 Mod ID：**mcmodsync**（为兼容 1.9.x 保留）

[更新日志](CHANGELOG.zh-CN.md) · [Changelog](CHANGELOG.md) · [文档导航](docs/README.md) · [English](docs/MCSYNC-2.0-README.en.md) · [发布与运维](docs/MCSYNC-2.0-OPERATIONS.md) · [需求与安全边界](docs/MCSYNC-2.0-REQUIREMENTS.md) · [开发结构](docs/MCSYNC-2.0-DEVELOPMENT.md) · [旧版升级](docs/中文使用指南.md)

## MCSync 管理什么

默认可纳入发布项目：

- `mods/`
- `resourcepacks/`
- `shaderpacks/`
- `kubejs/`
- `tacz/`
- `tlm_custom_pack/`
- 经过明确选择的 `config/` 与 `defaultconfigs/`
- 首次安装用的 `options.txt`
- 可选的 `servers.dat`

MCSync 不同步存档状态，也不应管理：

- `saves/`、世界区块、玩家数据和 SavedData
- Xaero/JourneyMap 探索数据
- 日志、崩溃报告、截图和缓存
- 启动器账户、Java 路径、内存参数和登录凭据
- 服务端密钥、令牌、白名单、OP 列表或私有地址

## 启动流程

1. MCSync 读取本地 `modsync.properties`。
2. 获取并严格解析 `mods-v5.json`。
3. 检查发布序号，拒绝降级和同序号分叉。
4. 显示新增推荐内容的选择界面。
5. 下载所选变更，并逐文件校验大小与 SHA-256。
6. 创建备份并执行一次原子事务。
7. 有 JAR 或启动期配置变化时退出，玩家重新启动后进入游戏。
8. 没有变化时继续正常加载 Minecraft。

MCSync 不承诺在 JVM 已加载 JAR 后热替换模组。涉及模组、KubeJS 启动脚本或启动期配置的更新都需要重启。

在 NeoForge 1.21.1 启动阶段，MCSync 会复用 NeoForge 的早期加载窗口显示检查、下载、缓存和哈希校验进度；不会额外弹出同步窗口。为适配 NeoForge 早期窗口的字体限制，这一阶段使用可显示的英文文件/状态标签，完整的中英文信息仍写入日志和 `.modsync` 状态文件。校验完成后，游戏窗口会显示退出倒计时，并明确提醒玩家不要立刻重新启动；随后 Minecraft 退出，隐藏 helper 只负责对已校验内容执行原子提交，因此退出后的提交阶段不再拥有 Minecraft 窗口。无法提供早期加载窗口的 Fabric、移动端或辅助进程环境会回退到标题、日志和 `.modsync` 状态文件。Minecraft 窗口内的推荐内容选择仍在可用的游戏界面阶段显示。

## 文件身份与上游匹配

文件内容是唯一可靠身份。

- 本地安装、v5 导入、备份和回滚以 **SHA-256** 为准。
- Modrinth 查询使用当前 JAR 的 **SHA-512**。
- CurseForge fingerprint 只用于平台文件候选匹配，不是字节级证明；发布器还必须下载候选并用当前 JAR 的大小与 SHA-256 复核。无法完成复核时放弃 CurseForge 来源，不生成可下载候选。
- 文件名、展示名称和版本字符串不用于确认上游文件。
- 唯一 `modId` 只可在版本升级后继承描述、必选状态等编辑元数据。
- 继承元数据后仍会用当前 JAR 重新查询上游，旧下载坐标不会直接沿用。
- 官方、镜像、直链和本地托管下载最终都必须命中 v5 中锁定的大小与 SHA-256。

发布器会在导出阶段把 Modrinth/CurseForge 的固定版本坐标解析成与当前 JAR 哈希一致的文件 URL。玩家启动时只获取服务器统一发布的 v5 清单并核对本地文件；本地文件正确时不访问模组站，缺失或损坏时才按清单内已经固定的文件候选下载。旧 v5 清单若没有固定 Modrinth 文件 URL，客户端仅为兼容才回退查询版本元数据。

只有直接位于 `mods/` 的 JAR 会查询 Modrinth、CurseForge 或其镜像。资源包、光影、KubeJS、配置、TACZ 和女仆模型包不会被误送到模组站匹配。

## 必须与推荐内容

- **必须**：缺失或哈希错误时必须修复；失败会阻止本次启动。
- **推荐**：首次出现或推荐集合新增时在 Minecraft 窗口内选择，默认全选；取消后不会强制恢复。
- 资源包和光影包也可以设为可选，并支持一键全选或取消。
- 已从当前客户端删除的 Mod 在导入旧 v5 时不会被复活。

## 发布一个 v5 版本

1. 准备并完整测试一个客户端根目录。
2. 运行 `java -jar MCSync-2.0.0.jar`。
3. 在“发布项目”中选择客户端根目录。
4. 在“Mods”页检查必选/推荐、双语描述和上游匹配结果。
5. 在“同步范围”中确认要管理的目录。
6. 在“配置 OTA”中只添加确实需要统一修改的配置项。
7. 如需服务器列表同步，选择经过测试的 `servers.dat`。
8. 在“验证与导出”中消除全部阻断项后导出。
9. 先上传不可变文件，再最后上传 `mods-v5.json`。

可在“发布项目”页选择**上一版完整发布输出目录**。MCSync 会自动读取其中最新的发布记录，并把它与当前待发布客户端目录中的实际文件按大小和 SHA-256 对比；不需要人工选择上一版 `mods-v5.json`。内容未变化的本地托管文件直接复用上一版不可变 URL，不再次复制进新版本目录；当前 `releases/<releaseSequence>/` 只包含新增或变化的升级文件。输出根目录同时生成 `UPLOAD-PLAN.json`、`UPLOAD-GUIDE.zh-CN.md` 和内容完全等价的 `UPLOAD-GUIDE.en.md`，明确新增/替换、复用、外部下载和删除路径。

重新发布时可以只在 Mods 页导入旧 `mods-v5.json`。它只继承可安全对应的模组元数据，不改变其他发布设置；随后按当前 JAR 重新计算哈希和匹配来源。

直接点击“扫描并识别升级”时，当前 `mods/` 会作为权威集合重新建立 Mods 表格。唯一 modId 的新版 JAR 会替换旧行并继承必须/推荐、双语描述、作用端和平台限制；下载来源仍按新版 JAR 的哈希重新匹配。若同一 modId 同时存在多个不同 JAR/版本，GUI 会把所有相关行标为冲突并阻止导出。

## 推荐的云端布局

```text
channel/stable/
├─ mods-v5.json
├─ releases/
│  └─ <release-sequence>/
│     ├─ mods/
│     ├─ resourcepacks/
│     ├─ shaderpacks/
│     ├─ kubejs/
│     └─ other-managed-files/
└─ server-list/
   ├─ serverlist.txt
   └─ servers.dat
```

旧 1.6.x、1.7 和 1.9.x 客户端使用的升级材料应继续留在它们原来的 URL。新 v5 目录不需要旁挂 v4 文件。参见[旧版升级指南](docs/中文使用指南.md)。

## 最小客户端配置

```properties
manifest=https://files.example.com/minecraft/channel/stable/mods-v5.json
language=auto
strict=true
requireManifest=true
syncResourcePacks=false
syncServerList=false
connectTimeoutSeconds=15
requestTimeoutSeconds=300
fileOperationRetries=12
```

真实地址和凭据不得提交到公开源码仓库。

## 构建与测试

Windows PowerShell：

```powershell
.\build.ps1
```

构建会运行完整测试并生成：

- `out/MCSync-2.0.0.jar`
- `out/MCSync-2.0.0-source.zip`
- 中文说明与示例配置

## 兼容性说明

产品名已经改为 MCSync，但以下技术入口为旧客户端升级保留：

- `mcmodsync` Mod ID
- `modsync.properties`
- `.modsync/`
- `MCModSync-Config.jar`
- v1-v4 清单解析
- 1.9.x 升级链

不要仅为统一命名而删除这些入口。

## License

见 [LICENSE](LICENSE)。
