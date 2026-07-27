# MCModSync

MCModSync 是一个 Fabric 客户端同步工具：游戏启动前，从你自行托管的 MD5 清单下载并校验 Mod、资源包和服务器列表。

> [!WARNING]
> **AI 辅助生成代码警示：** 本项目包含 AI 辅助生成或修改的代码与文档。AI 生成内容可能存在逻辑错误、兼容性问题或未知安全风险，不能视为安全审计结论。请在公开部署前自行审阅代码、扫描依赖、在隔离测试实例完整验证，并备份客户端。使用者自行负责清单、下载文件和服务器的可信性与安全性。

> [!IMPORTANT]
> 此公开版本不含原部署的站点、凭据、令牌、私有路径或个人作者信息。所有内置地址均为不可用的 `example.invalid` 占位符；**未配置你自己的地址时，工具会阻止启动，而不会连接任何真实服务。**

## 适用场景与前提

- 适用于 Fabric Loader `0.16+`、Minecraft `1.21.11+`、Java `21+` 的客户端实例。
- 你必须有可通过 HTTP/HTTPS 访问的文件托管空间；每位玩家的设备都要能访问它。
- 只把你有权分发的 Mod、资源包和服务器列表放入清单。
- `strict=true` 和 `requireManifest=true` 是推荐默认值：清单不可用、格式错误或校验失败时，客户端会停止启动。

## 给服主的快速开始

下面假定你的托管目录为 `https://files.example.com/minecraft/`。这是示例，请替换为你自己的域名和路径。

### 1. 准备发布文件

在一份**已测试完成**的客户端中，准备以下内容：

```text
发布目录/
├─ mods/                     # 要同步给玩家的 .jar
├─ resourcepacks/            # 可选：要同步的 .zip 资源包
└─ servers.dat               # 可选：要同步的服务器列表
```

不要把玩家的私人 Mod、存档、日志、账号文件或 `options.txt` 放入发布目录。发布前先在独立测试实例启动一次，确认这些 Mod 可以共存。

### 2. 生成清单

构建得到的 JAR 同时也是清单生成器。命令中的路径可使用绝对路径；有空格时请保留双引号。

```powershell
# 为 mods 目录生成 mods.txt
java -jar MCModSync-1.6.10.jar "D:\Publish\mods"

# 可选：为一个资源包生成 resourcepacks.txt
java -jar MCModSync-1.6.10.jar --resourcepack "D:\Publish\resourcepacks\server-pack.zip"

# 可选：为 servers.dat 生成 serverlist.txt
java -jar MCModSync-1.6.10.jar --serverlist "D:\Publish\servers.dat"
```

无参数运行 JAR 会打开图形化清单生成器。每次更换、增删或重命名任何发布文件后，都必须重新生成对应的清单。

生成的清单会记录 MD5：客户端下载完成后会再次校验，内容不一致的文件不会被当作成功同步。

### 3. 上传到文件托管空间

将文件和对应清单上传到同一公开目录。例如：

```text
https://files.example.com/minecraft/
├─ mods.txt
├─ fabric-api-*.jar
├─ your-mod-*.jar
├─ resourcepacks.txt          # 可选
├─ server-pack.zip            # 可选
├─ serverlist.txt             # 可选
└─ servers.dat                # 可选
```

清单中的文件名必须与托管文件名完全一致。客户端会以清单 URL 所在目录为基准解析下载文件，因此将清单与其文件放在同一目录最简单可靠。确认浏览器能直接下载文件，而不是跳转到需要登录、验证码或网页预览的页面。

### 4. 配置玩家客户端

1. 将 `MCModSync-1.6.10.jar` 放入玩家客户端实例的 `mods` 目录。
2. 将仓库中的 [`modsync.properties.example`](modsync.properties.example) 复制到**游戏根目录**（即包含 `mods` 文件夹的目录）。
3. 将复制件改名为 `modsync.properties`。
4. 将其中的示例地址改成你的真实地址，例如：

```properties
manifest=https://files.example.com/minecraft/mods.txt

syncResourcePacks=true
resourcePackManifest=https://files.example.com/minecraft/resourcepacks.txt

syncServerList=true
serverListManifest=https://files.example.com/minecraft/serverlist.txt

strict=true
requireManifest=true
```

`modsync.properties` 是部署私密配置，可能包含内部域名或带签名的 URL；不要把它提交到公开 Git 仓库。项目的 `.gitignore` 已默认忽略此文件。

### 5. 首次验证

先用一个备用客户端进行以下检查：

1. 退出 Minecraft 和启动器，备份整个实例，尤其是 `mods`、`resourcepacks`、`servers.dat`。
2. 启动客户端，确认能下载所有文件并完成 MD5 校验。
3. 关闭并再启动一次，确认没有重复下载。
4. 故意将清单地址改成不存在的地址，确认客户端会安全阻止启动；验证后恢复正确配置。
5. 再将真实地址指向玩家可访问的网络，才向全体玩家发布。

## 配置项说明

| 配置项 | 作用 | 建议 |
| --- | --- | --- |
| `manifest` | Mod 清单 URL | 必填，指向 `mods.txt` |
| `mobileManifest` | 手机启动器的 Mod 清单 URL | 可选；未设置时使用 `manifest` 的默认逻辑 |
| `syncResourcePacks` | 是否同步资源包 | 不使用时设为 `false` |
| `resourcePackManifest` | 资源包清单 URL | 启用资源包同步时必填 |
| `mobileResourcePackManifest` | 手机端资源包清单 URL | 可选 |
| `syncServerList` | 是否同步服务器列表 | 不使用时设为 `false` |
| `serverListManifest` | 服务器列表清单 URL | 启用服务器列表同步时必填 |
| `strict` | 对服务器移除的已管理 Mod 采取严格策略 | 推荐 `true` |
| `requireManifest` | 清单不可用时阻止启动 | 推荐且默认 `true` |
| `connectTimeoutSeconds` | 建连超时秒数 | 默认 `15` |
| `requestTimeoutSeconds` | 单次请求总超时秒数 | 默认 `300` |
| `maxFileBytes` | 允许下载的单文件最大字节数 | 按你的最大文件调整 |
| `fileOperationRetries` | 文件被占用时的重试次数 | 默认 `12` |

如果不需要资源包和服务器列表功能，请同时设置 `syncResourcePacks=false`、`syncServerList=false`。不要仅删除 URL 而保留相应同步开关为 `true`，否则启动会因无法获取必需清单而失败。

## 清单格式

一般不应手写清单，应使用 JAR 生成器。用于排错时，Mod 清单（v2）的每条记录为：

```text
# mcmod-sync-v2
<MD5>\t<fabric-mod-id 或 ->\t<jar 文件名>
```

资源包清单记录为 `<MD5>\t<zip 文件名>`，服务器列表清单只允许一个 `servers.dat` 条目。文件名不能包含路径分隔符或 Windows 禁用字符。

## 手机端

工具会尝试识别 Zalith、Pojav 等手机运行环境。若手机端与桌面端需要不同的 Mod 或资源包，可配置 `mobileManifest`、`mobileResourcePackManifest`；否则使用同一套清单即可。请务必在目标手机启动器上单独测试，因为 Java 运行时、内存限制和文件权限可能不同。

## 常见问题

**启动被阻止，提示无法取得清单**：检查 URL、DNS、HTTPS 证书、访问权限和防火墙；用玩家所在网络的浏览器直接打开 `mods.txt` 验证。

**每次启动都重新下载**：通常是托管文件内容改变后没有重新生成清单，或 CDN 缓存仍返回旧的清单/文件。重新生成并同时上传文件和清单，必要时刷新 CDN 缓存。

**下载到 HTML 页面而不是 JAR/ZIP**：文件托管链接不是直链，或需要登录。改用能返回原始文件字节的公开下载 URL。

**想临时关闭某项同步**：在 `modsync.properties` 将相应 `syncResourcePacks` 或 `syncServerList` 设为 `false`，不要删除整个配置文件。

更多操作细节见 [中文使用指南](docs/中文使用指南.md)。

## 从源码构建

在 Windows 和 Java 21 环境中运行：

```powershell
./build.ps1
```

它会编译、运行测试并将 JAR 写入 `build/dist/`。

## 许可证

本项目采用 [MIT License](LICENSE)。
