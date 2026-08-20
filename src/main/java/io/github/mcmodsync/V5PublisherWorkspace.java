package io.github.mcmodsync;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.RowFilter;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Primary MCSync 2.0 publisher workspace embedded in the executable JAR. */
final class V5PublisherWorkspace {
    private static final Set<String> NEVER_SCAN_ROOTS = Set.of(
            "saves", "world", "logs", "crash-reports", "screenshots", "natives", "libraries",
            "assets", "versions", "downloads", "backups", "simplebackups", ".minecraft");
    private static final String[] FILE_SIDES = {"client", "both", "server"};

    private final JFrame owner;
    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final JTextField gameRoot = new JTextField();
    private final JTextField outputDirectory = new JTextField();
    private final JTextField previousOutputDirectory = new JTextField();
    private final JTextField releaseId = new JTextField("motiquies-2.0.0-ota.1");
    private final JSpinner releaseSequence = new JSpinner(new SpinnerNumberModel(
            PublisherProjectV5.currentTimeReleaseSequence(), 1L, Long.MAX_VALUE, 1L));
    private final JCheckBox autoReleaseSequence = new JCheckBox("导出时按当前系统时间刷新序号", true);
    private final JTextField minimumVersion = new JTextField(BuildInfo.VERSION);
    private final JTextField publicBaseUrl = new JTextField("https://files.example.com/mcsync");
    private final JTextField stableManifestPath = new JTextField("channel/stable/mods-v5.json");
    private final JTextField legacyV4Path = new JTextField("legacy/1.9/mods-v4.txt");
    private final JTextField legacyV2Path = new JTextField("legacy/1.6/mods.txt");
    private final JCheckBox syncServerList = new JCheckBox("同步服务器列表（保留玩家自定义条目）", true);
    private final JTextField serverListSource = new JTextField();
    private final JTextField serverListManifestPath = new JTextField("server-list/serverlist.txt");
    private final JCheckBox generateLegacyGateways = new JCheckBox("生成 1.9.x 和 1.6.x/1.7.x 永久升级入口", true);
    private final FileModel files = new FileModel();
    private final ScopeModel scopes = new ScopeModel();
    private final ConfigModel config = new ConfigModel();
    private final JTabbedPane workspaceTabs = new JTabbedPane();
    private final JTable fileTable = new JTable(files);
    private final JTable modsTable = new JTable(files);
    private final JTable scopeTable = new JTable(scopes);
    private final JTable configTable = new JTable(config);
    private final JProgressBar modMatchProgress = new JProgressBar(0, 100);
    private final JProgressBar publishProgress = new JProgressBar(0, 100);
    private final JLabel publishProgressDetail = new JLabel("尚未开始发布");
    private final JTextArea validation = new JTextArea();
    private final JLabel summary = new JLabel();
    private final PublisherModAutoMatcher modMatcher = new PublisherModAutoMatcher();
    private Path projectFile;

    private V5PublisherWorkspace(JFrame owner) {
        this.owner = owner;
        scopes.addDefaults();
        buildUi();
        refreshSummary();
    }

    static JPanel create(JFrame owner) {
        return new V5PublisherWorkspace(owner).root;
    }

    private void buildUi() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel heading = new JPanel(new BorderLayout());
        JLabel title = new JLabel("MCSync 2.0 OTA 发布工作台");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        heading.add(title, BorderLayout.WEST);
        heading.add(summary, BorderLayout.EAST);
        root.add(heading, BorderLayout.NORTH);

        workspaceTabs.addTab("发布项目", projectPanel());
        workspaceTabs.addTab("Mods", modsPanel());
        workspaceTabs.addTab("其他文件", filesPanel());
        workspaceTabs.addTab("同步范围", scopesPanel());
        workspaceTabs.addTab("配置 OTA", configPanel());
        workspaceTabs.addTab("远端与旧版升级", remotePanel());
        workspaceTabs.addTab("验证与导出", exportPanel());
        root.add(workspaceTabs, BorderLayout.CENTER);
    }

    private JPanel projectPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(18, 18, 10, 18));
        GridBagConstraints c = constraints();
        addPathRow(form, c, 0, "本次待发布客户端目录：", gameRoot, "选择目录", true);
        addPathRow(form, c, 1, "空的发布输出目录：", outputDirectory, "选择目录", false);
        addFieldRow(form, c, 2, "发布 ID：", releaseId);
        c.gridx = 0;
        c.gridy = 3;
        c.weightx = 0;
        form.add(new JLabel("防降级序号："), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(releaseSequence, c);
        c.gridx = 2;
        c.weightx = 0;
        form.add(autoReleaseSequence, c);
        addFieldRow(form, c, 4, "最低 MCSync 版本：", minimumVersion);
        addBaselinePathRow(form, c, 5, "上一版完整发布输出/升级包（推荐）：", previousOutputDirectory, "选择基线");

        JTextArea note = new JTextArea(
                "此工作台直接产生 schema-v5 发布。releaseSequence 只能增加，客户端会拒绝降级。\n"
                        + "自动扫描读取当前同步范围中的普通目录与文件；config/defaultconfigs/configureddefaults 也会参与，"
                        + "但凭据、身份、备份和运行态缓存会按路径与内容黑名单跳过。精确修复仍建议使用“配置 OTA”。\n"
                        + "增量发布以“上一版完整发布输出/升级包”对比当前客户端；程序只需要其中完整的 manifest-v5.json，\n"
                        + "旧文件本体不必重复携带。ZIP 升级包、releases/<序号> 目录和完整输出目录均可作为基线；只有差分清单而没有完整索引时会阻止导出。\n"
                        + "未变化文件复用旧不可变地址，当前 releases/<序号>/ 只输出新增或变化的文件。");
        note.setEditable(false);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        note.setOpaque(false);
        note.setBorder(BorderFactory.createEmptyBorder(10, 22, 10, 22));
        panel.add(form, BorderLayout.NORTH);
        panel.add(note, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton continueV5 = new JButton("仅导入旧 v5 的描述与设置…");
        JButton load = new JButton("打开发布项目…");
        JButton save = new JButton("保存发布项目…");
        continueV5.addActionListener(event -> importExistingV5Manifest());
        load.addActionListener(event -> loadProject());
        save.addActionListener(event -> saveProject());
        actions.add(continueV5);
        actions.add(load);
        actions.add(save);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel filesPanel() {
        configureCombo(fileTable, 6, FILE_SIDES);
        installKindFilter(fileTable, false);
        fileTable.setRowHeight(23);

        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JTextArea help = new JTextArea(
                "这里仅管理资源包、光影、KubeJS、模型包和其他普通文件。它们始终由发布者托管，"
                        + "不会接触 Modrinth、CurseForge 或镜像接口。资源包和光影包可设为可选，"
                        + "玩家会与推荐 Mod 一起在 Minecraft 窗口内选择；其他玩法文件保持必须。"
                        + "mods 请在独立的 Mods 选项卡管理。");
        help.setEditable(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setRows(2);
        help.setOpaque(false);
        panel.add(help, BorderLayout.NORTH);
        panel.add(new JScrollPane(fileTable), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton scan = new JButton("扫描安全内容目录");
        JButton add = new JButton("添加文件…");
        JButton required = new JButton("所选设为必须");
        JButton optional = new JButton("所选资源/光影设为可选");
        JButton remove = new JButton("移除选中");
        scan.addActionListener(event -> scanSafeRoots(scan));
        add.addActionListener(event -> addFiles());
        required.addActionListener(event -> setSelectedContentRequired(true));
        optional.addActionListener(event -> setSelectedContentRequired(false));
        remove.addActionListener(event -> removeSelected(fileTable, files.rows));
        buttons.add(scan);
        buttons.add(add);
        buttons.add(required);
        buttons.add(optional);
        buttons.add(remove);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel modsPanel() {
        configureCombo(modsTable, 6, FILE_SIDES);
        installKindFilter(modsTable, true);
        modsTable.setRowHeight(23);

        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JTextArea help = new JTextArea(
                "必须 Mod 会始终同步；推荐 Mod 由玩家在 Minecraft 窗口内首次启动或推荐清单新增时选择，"
                        + "默认全选。Modrinth 按哈希匹配；CurseForge 先定位候选，导出时必须下载并通过大小/SHA-256 复核。"
                        + "无法复核或未匹配的自制、适配 Mod 回退为本地托管。"
                        + "中文描述永不被平台英文覆盖；扫描会把唯一 modId 的新版 JAR 识别为替换升级并继承人工设置。"
                        + "同一 modId 出现多个 JAR 时会标记冲突并阻止导出。");
        help.setEditable(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setRows(3);
        help.setOpaque(false);
        panel.add(help, BorderLayout.NORTH);
        panel.add(new JScrollPane(modsTable), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton scan = new JButton("扫描并识别升级");
        JButton importV4 = new JButton("从 mods-v4.txt 导入");
        JButton importV5 = new JButton("从 mods-v5.json 导入模组信息…");
        JButton rematch = new JButton("自动匹配全部 Mod");
        JButton required = new JButton("所选设为必须");
        JButton recommended = new JButton("所选设为推荐");
        JButton editMetadata = new JButton("编辑名称与双语描述…");
        JButton remove = new JButton("移除选中");
        scan.addActionListener(event -> scanMods(scan));
        importV4.addActionListener(event -> importV4Catalog());
        importV5.addActionListener(event -> importV5ModCatalog());
        rematch.addActionListener(event -> autoMatchMods(rematch));
        required.addActionListener(event -> setSelectedModKind(true));
        recommended.addActionListener(event -> setSelectedModKind(false));
        editMetadata.addActionListener(event -> editSelectedModMetadata());
        remove.addActionListener(event -> {
            removeSelected(modsTable, files.rows);
            refreshModConflicts();
            refreshSummary();
        });
        buttons.add(scan);
        buttons.add(importV4);
        buttons.add(importV5);
        buttons.add(rematch);
        buttons.add(required);
        buttons.add(recommended);
        buttons.add(editMetadata);
        buttons.add(remove);
        modMatchProgress.setStringPainted(true);
        modMatchProgress.setString("等待扫描");
        JPanel footer = new JPanel(new BorderLayout(6, 4));
        footer.add(buttons, BorderLayout.NORTH);
        footer.add(modMatchProgress, BorderLayout.SOUTH);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private void installKindFilter(JTable table, boolean modsOnly) {
        TableRowSorter<FileModel> sorter = new TableRowSorter<>(files);
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends FileModel, ? extends Integer> entry) {
                boolean mod = files.rows.get(entry.getIdentifier()).kind.equals("mod");
                return modsOnly == mod;
            }
        });
        table.setRowSorter(sorter);
    }

    private JPanel scopesPanel() {
        configureCombo(scopeTable, 1, new String[]{"managed", "additive", "first-install"});
        scopeTable.setRowHeight(24);
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(new JScrollPane(scopeTable), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        JTextArea excluded = new JTextArea(
                "策略：managed 会移除 MCSync 上一版拥有、但新清单已删除的文件；additive 只添加/更新清单文件，"
                        + "不因清单省略而删除；first-install 只在目标不存在时安装，之后保留玩家本地版本。\n"
                        + "“扫描安全内容目录”会读取这里声明的普通目录或文件；mods 由 Mods 页扫描。"
                        + "config/defaultconfigs/configureddefaults 会正常扫描，敏感路径和敏感配置键按黑名单跳过并报告。\n"
                        + "永久排除：saves/world/playerdata/advancements/stats/logs/crash-reports/screenshots/"
                        + "journeymap/xaero 玩家探索数据、运行库与启动器缓存。\n"
                        + "内置于 Mod JAR 中的默认资源包/数据包/模型包不单独同步，由 mods 文件本身管理。");
        excluded.setEditable(false);
        excluded.setLineWrap(true);
        excluded.setWrapStyleWord(true);
        excluded.setRows(6);
        excluded.setOpaque(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton add = new JButton("添加范围");
        JButton remove = new JButton("移除选中");
        add.addActionListener(event -> { scopes.rows.add(new ScopeRow("custom", "additive")); scopes.fireTableDataChanged(); });
        remove.addActionListener(event -> removeSelected(scopeTable, scopes.rows));
        buttons.add(add);
        buttons.add(remove);
        footer.add(excluded, BorderLayout.CENTER);
        footer.add(buttons, BorderLayout.SOUTH);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel configPanel() {
        configTable.setAutoCreateRowSorter(true);
        configTable.setRowHeight(23);
        configureCombo(configTable, 1, new String[]{"config-set", "config-merge", "file-replace"});
        configureCombo(configTable, 2, new String[]{"toml", "json", "properties", "json5", "snbt", "text", "binary"});
        configureCombo(configTable, 4, new String[]{"boolean", "integer", "decimal", "string", "array", "object", "binary"});
        configureCombo(configTable, 8, new String[]{"create", "skip", "block"});
        configureCombo(configTable, 9, new String[]{"block", "keep-local", "report", "force", "replace-if-expected"});
        configureCombo(configTable, 10, new String[]{"client", "integrated_server", "dedicated_server", "both"});
        configureCombo(configTable, 11, new String[]{"prelaunch", "first-install"});

        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(new JScrollPane(configTable), BorderLayout.CENTER);
        JTextArea help = new JTextArea(
                "expected/desired 使用 JSON 值（字符串可直接输入）。file-replace 必须填 expectedSha256 或 absent；"
                        + "密钥、token、玩家身份和存档状态不允许进入 OTA。");
        help.setEditable(false);
        help.setOpaque(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(help, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton add = new JButton("添加配置操作");
        JButton remove = new JButton("移除选中");
        add.addActionListener(event -> { config.rows.add(ConfigRow.defaults()); config.fireTableDataChanged(); });
        remove.addActionListener(event -> removeSelected(configTable, config.rows));
        buttons.add(add);
        buttons.add(remove);
        footer.add(buttons, BorderLayout.SOUTH);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel remotePanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(18, 18, 8, 18));
        GridBagConstraints c = constraints();
        addFieldRow(form, c, 0, "公开 HTTPS 根地址：", publicBaseUrl);
        addFieldRow(form, c, 1, "2.0 稳定入口：", stableManifestPath);
        addFieldRow(form, c, 2, "1.9.x 升级入口：", legacyV4Path);
        addFieldRow(form, c, 3, "1.6.x/1.7.x 升级入口：", legacyV2Path);
        addFilePathRow(form, c, 4, "服务器列表源：", serverListSource, "选择 servers.dat");
        addFieldRow(form, c, 5, "服务器列表清单路径：", serverListManifestPath);
        c.gridx = 1;
        c.gridy = 6;
        c.gridwidth = 2;
        form.add(syncServerList, c);
        c.gridy = 7;
        form.add(generateLegacyGateways, c);
        panel.add(form, BorderLayout.NORTH);

        JTextArea explanation = new JTextArea(
                "输出会按下列布局生成：\n\n"
                        + "releases/<releaseSequence>/       不可变的版本文件与 manifest-v5.json\n"
                        + "channel/stable/mods-v5.json        2.0 客户端的正式 v5 入口\n"
                        + "legacy/1.9/mods-v4.txt            1.8/1.9 升级网关\n"
                        + "legacy/1.6/mods.txt               1.6/1.7 v2 升级网关\n"
                        + "server-list/serverlist.txt        服务器列表校验清单\n"
                        + "server-list/servers.dat           服务器列表合并源\n"
                        + "client-modsync.properties         客户端/配置引导用的地址片段\n\n"
                        + "新版只读取 mods-v5.json；旧版升级材料单独生成在 legacy/，不参与新版入口。\n"
                        + "发布时先上传 releases 和新版 JSON，再把 legacy/ 下的材料放到你维护的旧版地址。");
        explanation.setEditable(false);
        explanation.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setBorder(BorderFactory.createTitledBorder("云端布局与发布顺序"));
        panel.add(new JScrollPane(explanation), BorderLayout.CENTER);
        return panel;
    }

    private JPanel exportPanel() {
        validation.setEditable(false);
        validation.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        validation.setLineWrap(true);
        validation.setWrapStyleWord(true);
        validation.setText("点击“验证项目”检查路径、Mod 自动匹配结果和配置操作。\n");
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(validation), releaseChecklist());
        split.setResizeWeight(0.72);
        split.setBorder(null);
        panel.add(split, BorderLayout.CENTER);
        publishProgress.setStringPainted(true);
        publishProgress.setString("等待发布");
        JPanel progressPanel = new JPanel(new BorderLayout(8, 3));
        progressPanel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        progressPanel.add(publishProgressDetail, BorderLayout.NORTH);
        progressPanel.add(publishProgress, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton validate = new JButton("验证项目");
        JButton save = new JButton("保存项目 JSON…");
        JButton publish = new JButton("验证并导出 OTA");
        validate.addActionListener(event -> showValidation());
        save.addActionListener(event -> saveProject());
        publish.addActionListener(event -> publish(publish));
        actions.add(validate);
        actions.add(save);
        actions.add(publish);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(progressPanel, BorderLayout.CENTER);
        footer.add(actions, BorderLayout.SOUTH);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JScrollPane releaseChecklist() {
        JTextArea checklist = new JTextArea(
                "导出门禁\n"
                        + "  • mods/*.jar 已完成 SHA-256 验证的平台匹配或回退本地发布\n"
                        + "  • 其他文件全部使用本地发布，不接触模组站\n"
                        + "  • 中国镜像仅是第三方传输候选，保留官方回退\n"
                        + "  • required 文件不能使用 manual\n"
                        + "  • 配置 OTA 有前像、冲突策略和作用端\n"
                        + "  • options.txt 仅首装写入；服务器列表源与清单已校验\n"
                        + "  • 输出目录为空，不混入旧发布");
        checklist.setEditable(false);
        checklist.setOpaque(false);
        return new JScrollPane(checklist);
    }

    private void addPathRow(
            JPanel form, GridBagConstraints c, int row, String label, JTextField field, String buttonText,
            boolean existing) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(field, c);
        JButton button = new JButton(buttonText);
        c.gridx = 2;
        c.weightx = 0;
        form.add(button, c);
        button.addActionListener(event -> chooseDirectory(field, existing));
    }

    private void addFilePathRow(
            JPanel form, GridBagConstraints c, int row, String label, JTextField field, String buttonText) {
        addFilePathRow(form, c, row, label, field, buttonText, "选择测试客户端的 servers.dat");
    }

    private void addBaselinePathRow(
            JPanel form, GridBagConstraints c, int row, String label, JTextField field, String buttonText) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(field, c);
        JButton button = new JButton(buttonText);
        c.gridx = 2;
        c.weightx = 0;
        form.add(button, c);
        button.addActionListener(event -> chooseBaseline(field));
    }

    private void addFilePathRow(
            JPanel form, GridBagConstraints c, int row, String label, JTextField field, String buttonText,
            String dialogTitle) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(field, c);
        JButton button = new JButton(buttonText);
        c.gridx = 2;
        c.weightx = 0;
        form.add(button, c);
        button.addActionListener(event -> chooseFile(field, dialogTitle));
    }

    private static void addFieldRow(JPanel form, GridBagConstraints c, int row, String label, JTextField field) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1;
        form.add(field, c);
        c.gridwidth = 1;
    }

    private static GridBagConstraints constraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private void chooseDirectory(JTextField target, boolean existing) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle(existing ? "选择游戏根目录" : "选择空的发布输出目录");
        if (!target.getText().isBlank()) chooser.setCurrentDirectory(Path.of(target.getText()).toFile());
        int result = existing ? chooser.showOpenDialog(owner) : chooser.showSaveDialog(owner);
        if (result == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
            if (target == gameRoot && serverListSource.getText().isBlank()) {
                Path candidate = Path.of(target.getText()).resolve(ServerListManifest.FILE_NAME);
                if (Files.isRegularFile(candidate)) serverListSource.setText(candidate.toString());
            }
        }
    }

    private void chooseFile(JTextField target, String title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setDialogTitle(title);
        if (!target.getText().isBlank()) chooser.setSelectedFile(Path.of(target.getText()).toFile());
        if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
        }
    }

    private void chooseBaseline(JTextField target) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setDialogTitle("选择上一版完整输出目录或 ZIP 升级包");
        if (!target.getText().isBlank()) chooser.setSelectedFile(Path.of(target.getText()).toFile());
        if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
        }
    }

    private void scanSafeRoots(JButton button) {
        if (scopeTable.isEditing() && !scopeTable.getCellEditor().stopCellEditing()) {
            showError("当前同步范围尚未完成编辑，请确认路径和策略后重试。");
            return;
        }
        Path rootPath;
        try {
            rootPath = requireGameRoot();
        } catch (IOException failure) {
            showError(failure.getMessage());
            return;
        }
        button.setEnabled(false);
        validation.append("\n正在扫描安全内容目录…\n");
        List<String> declaredScanPaths = publisherContentScanPaths(
                scopes.rows.stream().map(row -> row.path).toList());
        new SwingWorker<ContentScanResult, Void>() {
            @Override
            protected ContentScanResult doInBackground() throws Exception {
                ArrayList<FileRow> found = new ArrayList<>();
                ArrayList<String> skipped = new ArrayList<>();
                LinkedHashMap<String, String> scopeOverrides = new LinkedHashMap<>();
                Set<String> existing = files.normalizedPaths();
                Path options = rootPath.resolve("options.txt");
                if (Files.isRegularFile(options, LinkOption.NOFOLLOW_LINKS)
                        && existing.add("options.txt")) {
                    FileRow row = FileRow.scanned("options.txt", "support");
                    row.confirmed = true;
                    row.applyLocal("首装保留：不覆盖玩家已有 options.txt");
                    found.add(row);
                }
                for (String declaredPath : declaredScanPaths) {
                    Path scanRoot = rootPath.resolve(declaredPath);
                    List<Path> candidates;
                    if (Files.isRegularFile(scanRoot, LinkOption.NOFOLLOW_LINKS)) {
                        candidates = List.of(scanRoot);
                    } else if (Files.isDirectory(scanRoot, LinkOption.NOFOLLOW_LINKS)) {
                        try (var stream = Files.walk(scanRoot)) {
                            candidates = stream
                                    .filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                                    .sorted()
                                    .toList();
                        }
                    } else {
                        continue;
                    }
                    for (Path path : candidates) {
                            if (Files.isSymbolicLink(path)) continue;
                            String relative = rootPath.relativize(path).toString().replace('\\', '/');
                            String first = relative.split("/", 2)[0].toLowerCase(Locale.ROOT);
                            if (NEVER_SCAN_ROOTS.contains(first) || !existing.add(relative.toLowerCase(Locale.ROOT))) continue;
                            byte[] inspected = SensitiveDataPolicy.isConfigTree(relative)
                                    ? Files.readAllBytes(path) : null;
                            String exclusion = SensitiveDataPolicy.publisherScanExclusionReason(relative, inspected);
                            if (exclusion != null) {
                                skipped.add(relative + " — " + exclusion);
                                continue;
                            }
                            if (SensitiveDataPolicy.isConfigTree(relative)) {
                                PublisherConfigClassifier.Decision decision =
                                        PublisherConfigClassifier.classify(relative, inspected);
                                if (decision.action() == PublisherConfigClassifier.Action.KEY_LEVEL_ONLY) {
                                    skipped.add(relative + " — " + decision.reason());
                                    continue;
                                }
                                if (decision.action() == PublisherConfigClassifier.Action.FIRST_INSTALL) {
                                    scopeOverrides.put(relative, "first-install");
                                }
                            }
                            FileRow row = FileRow.scanned(relative, kindFor(relative));
                            if (!PublisherModAutoMatcher.isModArtifact(relative, row.kind)) {
                                row.confirmed = true;
                                row.applyLocal("非 Mod 文件固定本地托管");
                            }
                            found.add(row);
                    }
                }
                List<FileRow> modRows = found.stream()
                        .filter(row -> PublisherModAutoMatcher.isModArtifact(row.path, row.kind)).toList();
                Map<Path, PublisherModAutoMatcher.Match> matches = modMatcher.matchAll(
                        modRows.stream().map(row -> rootPath.resolve(row.path)).toList(),
                        V5PublisherWorkspace.this::updateModMatchProgress);
                for (FileRow row : modRows) {
                    PublisherModAutoMatcher.Match match = matches.get(rootPath.resolve(row.path));
                    if (match == null) match = new PublisherModAutoMatcher.Match(
                            PublisherModAutoMatcher.localDownload(), "未匹配，使用本地文件");
                    row.applyMatch(match);
                }
                return new ContentScanResult(
                        List.copyOf(found), List.copyOf(skipped), Map.copyOf(scopeOverrides));
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                try {
                    ContentScanResult result = get();
                    List<FileRow> found = result.found();
                    result.scopeOverrides().forEach(V5PublisherWorkspace.this::ensureScopePolicy);
                    files.rows.addAll(found);
                    files.rows.sort(Comparator.comparing(row -> row.path));
                    files.fireTableDataChanged();
                    validation.append("扫描完成：新增 " + found.size()
                            + " 个文件；按安全黑名单跳过 " + result.skipped().size() + " 个文件。"
                            + "Mod 已完成 SHA-256 复核或回退本地托管，其他文件固定本地托管。\n");
                    int shown = Math.min(30, result.skipped().size());
                    for (int index = 0; index < shown; index++) {
                        validation.append("  跳过: " + result.skipped().get(index) + "\n");
                    }
                    if (result.skipped().size() > shown) {
                        validation.append("  另有 " + (result.skipped().size() - shown) + " 个黑名单命中未展开。\n");
                    }
                    refreshSummary();
                } catch (Exception failure) {
                    showError(cause(failure).getMessage());
                }
            }
        }.execute();
    }

    static List<String> publisherContentScanPaths(List<String> declaredScopes) {
        ArrayList<String> candidates = new ArrayList<>();
        HashSet<String> unique = new HashSet<>();
        for (String declared : declaredScopes) {
            if (declared == null) continue;
            String normalized = declared.strip().replace('\\', '/');
            while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
            if (normalized.isBlank() || normalized.startsWith("/") || normalized.contains("..")) continue;
            String lower = normalized.toLowerCase(Locale.ROOT);
            String first = lower.split("/", 2)[0];
            if (first.equals("mods") || NEVER_SCAN_ROOTS.contains(first)
                    || lower.equals("options.txt") || lower.equals(".modsync") || lower.startsWith(".modsync/")) {
                continue;
            }
            if (unique.add(lower)) candidates.add(normalized);
        }
        candidates.sort(Comparator.comparingInt(String::length).thenComparing(String::compareToIgnoreCase));
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        for (String candidate : candidates) {
            String lower = candidate.toLowerCase(Locale.ROOT);
            boolean nested = roots.stream().map(value -> value.toLowerCase(Locale.ROOT))
                    .anyMatch(parent -> lower.equals(parent) || lower.startsWith(parent + "/"));
            if (!nested) roots.add(candidate);
        }
        return List.copyOf(roots);
    }

    private void ensureScopePolicy(String path, String policy) {
        String normalized = path.replace('\\', '/');
        ScopeRow existing = scopes.rows.stream()
                .filter(row -> row.path.replace('\\', '/').equalsIgnoreCase(normalized))
                .findFirst().orElse(null);
        if (existing == null) {
            scopes.rows.add(new ScopeRow(normalized, policy));
        } else {
            existing.policy = policy;
        }
        scopes.fireTableDataChanged();
    }

    private record ContentScanResult(
            List<FileRow> found,
            List<String> skipped,
            Map<String, String> scopeOverrides) {
    }

    private void scanMods(JButton button) {
        Path rootPath;
        try {
            rootPath = requireGameRoot();
        } catch (IOException failure) {
            showError(failure.getMessage());
            return;
        }
        button.setEnabled(false);
        validation.append("\n正在扫描 mods、读取本地元数据并识别替换升级…\n");
        new SwingWorker<List<FileRow>, Void>() {
            @Override
            protected List<FileRow> doInBackground() throws Exception {
                return discoverCurrentMods(rootPath);
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                try {
                    List<FileRow> found = get();
                    ModScanResult result = reconcileScannedMods(found);
                    files.rows.sort(Comparator.comparing(row -> row.path));
                    files.fireTableDataChanged();
                    refreshSummary();
                    validation.append("mods 扫描完成：当前 " + found.size() + " 个 Mod；继承设置 "
                            + result.inherited() + "，其中替换升级 " + result.replaced()
                            + "；新增 " + result.added() + "；移除旧行 " + result.removed()
                            + "；冲突 " + result.conflicts() + "。\n");
                    if (result.conflicts() > 0) showModConflictWarning();
                    autoMatchMods(null);
                } catch (Exception failure) {
                    showError(cause(failure).getMessage());
                }
            }
        }.execute();
    }

    private List<FileRow> discoverMods(Path rootPath) throws IOException {
        Set<String> existing = files.normalizedPaths();
        return discoverCurrentMods(rootPath).stream()
                .filter(row -> existing.add(row.path.toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static List<FileRow> discoverCurrentMods(Path rootPath) throws IOException {
        ArrayList<FileRow> found = new ArrayList<>();
        Path mods = rootPath.resolve("mods");
        if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) return found;
        try (var stream = Files.list(mods)) {
            for (Path jar : stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted().toList()) {
                if (Files.isSymbolicLink(jar)) continue;
                String relative = rootPath.relativize(jar).toString().replace('\\', '/');
                FileRow row = FileRow.scanned(relative, "mod");
                populateLocalModMetadata(jar, row);
                found.add(row);
            }
        }
        return found;
    }

    private static void populateLocalModMetadata(Path jar, FileRow row) {
        row.modId = ModMetadata.readModId(jar);
        row.displayName = ModMetadata.readName(jar);
        row.modVersion = ModMetadata.readVersion(jar);
        String description = ModMetadata.readDescription(jar);
        if (containsHan(description)) row.descriptionZh = description;
        else row.descriptionEn = description;
        row.required = !ModMetadata.recommendedByMetadata(jar);
        row.restart = true;
        if (!row.required) row.matchDetail = "客户端可选元数据：默认推荐";
    }

    private ModScanResult reconcileScannedMods(List<FileRow> current) {
        List<FileRow> existing = files.rows.stream().filter(row -> row.kind.equals("mod")).toList();
        PublisherModUpgradePlanner.Plan plan = PublisherModUpgradePlanner.plan(
                existing.stream().map(row -> new PublisherModUpgradePlanner.ExistingMod(
                        row.path, row.modId, row.modVersion)).toList(),
                current.stream().map(row -> new PublisherModUpgradePlanner.CurrentMod(
                        row.path, row.modId, row.modVersion)).toList());
        Map<String, FileRow> existingByPath = new LinkedHashMap<>();
        for (FileRow row : existing) {
            existingByPath.put(PublisherModUpgradePlanner.normalizePath(row.path), row);
        }

        int inherited = 0;
        int replaced = 0;
        for (FileRow row : current) {
            String currentPath = PublisherModUpgradePlanner.normalizePath(row.path);
            String oldPath = plan.inheritedFromByCurrentPath().get(currentPath);
            if (oldPath != null) {
                FileRow previous = existingByPath.get(oldPath);
                if (previous != null) {
                    inheritScannedModSettings(row, previous, !currentPath.equals(oldPath));
                    inherited++;
                    if (!currentPath.equals(oldPath)) replaced++;
                }
            }
            String conflict = plan.conflictByCurrentPath().get(currentPath);
            if (conflict != null) row.markConflict(conflict);
        }

        files.rows.removeIf(row -> row.kind.equals("mod"));
        files.rows.addAll(current);
        refreshModConflicts();
        int conflictGroups = new HashSet<>(plan.conflictByCurrentPath().values()).size();
        return new ModScanResult(inherited, replaced, plan.newCurrentPaths().size(),
                plan.staleExistingPaths().size(), conflictGroups);
    }

    private static void inheritScannedModSettings(FileRow current, FileRow previous, boolean replacement) {
        current.required = previous.required;
        current.restart = previous.restart;
        current.side = previous.side;
        if (!previous.displayName.isBlank()) current.displayName = previous.displayName;
        if (!previous.descriptionZh.isBlank()) current.descriptionZh = previous.descriptionZh;
        if (!previous.descriptionEn.isBlank()) current.descriptionEn = previous.descriptionEn;
        current.incompatiblePlatforms.clear();
        current.incompatiblePlatforms.addAll(previous.incompatiblePlatforms);
        current.chinaMirror = previous.chinaMirror;
        current.resetDownloadMatch(replacement
                ? "检测到替换升级：" + previous.path + " → " + current.path + "；已继承设置，等待按新 JAR 重新匹配"
                : "已继承现有设置，等待按当前 JAR 重新匹配");
    }

    private void importV4Catalog() {
        Path rootPath;
        try {
            rootPath = requireGameRoot();
        } catch (IOException failure) {
            showError(failure.getMessage());
            return;
        }
        JFileChooser chooser = new JFileChooser(rootPath.toFile());
        chooser.setDialogTitle("选择旧版 mods-v4.txt");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        Path manifestPath = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        try {
            ModManifest legacy = ModManifest.parse(Files.readString(manifestPath, StandardCharsets.UTF_8));
            if (!legacy.supportsRecommendations()) {
                throw new IOException("所选文件不是带必须/推荐元数据的 mods-v4.txt。 ");
            }
            files.rows.addAll(discoverMods(rootPath));
            Map<String, ManifestEntry> byModId = new LinkedHashMap<>();
            Map<String, ManifestEntry> byFile = new LinkedHashMap<>();
            for (ManifestEntry entry : legacy.entries()) {
                if (!entry.modId().isBlank()) byModId.put(entry.modId().toLowerCase(Locale.ROOT), entry);
                byFile.put(entry.fileName().toLowerCase(Locale.ROOT), entry);
            }
            int matched = 0;
            for (FileRow row : files.rows) {
                if (!row.kind.equals("mod")) continue;
                ManifestEntry entry = row.modId.isBlank() ? null : byModId.get(row.modId.toLowerCase(Locale.ROOT));
                if (entry == null) entry = byFile.get(Path.of(row.path).getFileName().toString().toLowerCase(Locale.ROOT));
                if (entry == null) continue;
                row.required = !entry.recommended();
                if (!entry.displayName().isBlank()) row.displayName = entry.displayName();
                if (row.modVersion.isBlank() && !entry.version().isBlank()) row.modVersion = entry.version();
                if (!entry.descriptionZh().isBlank()) row.descriptionZh = entry.descriptionZh();
                if (row.descriptionEn.isBlank() && !entry.descriptionEn().isBlank()) {
                    row.descriptionEn = entry.descriptionEn();
                }
                row.incompatiblePlatforms.clear();
                for (ClientPlatform platform : entry.incompatiblePlatforms()) {
                    row.incompatiblePlatforms.add(switch (platform) {
                        case WINDOWS -> "windows";
                        case MAC -> "macos";
                        case LINUX -> "linux";
                        case MOBILE -> "android";
                    });
                }
                matched++;
            }
            files.rows.sort(Comparator.comparing(row -> row.path));
            refreshModConflicts();
            files.fireTableDataChanged();
            refreshSummary();
            validation.append("已从 " + manifestPath.getFileName() + " 导入 " + matched
                    + " 个当前 Mod 的必须/推荐、双语描述和平台限制；文件哈希仍以当前客户端为准。\n");
            autoMatchMods(null);
        } catch (Exception failure) {
            showError("导入 mods-v4.txt 失败：" + cause(failure).getMessage());
        }
    }

    private void importV5ModCatalog() {
        Path rootPath;
        try {
            rootPath = requireGameRoot();
        } catch (IOException failure) {
            showError(failure.getMessage());
            return;
        }
        JFileChooser chooser = new JFileChooser(rootPath.toFile());
        chooser.setDialogTitle("选择用于继承模组信息的 mods-v5.json");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        Path manifestPath = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        try {
            ReleaseManifestV5 manifest = ReleaseManifestV5.parse(Files.readAllBytes(manifestPath));
            List<ReleaseManifestV5.FileEntry> imported = manifest.files().stream()
                    .filter(entry -> entry.kind().equals("mod"))
                    .toList();
            List<FileRow> current = discoverCurrentMods(rootPath);
            List<V5ModCatalogMatcher.CurrentMod> currentKeys = new ArrayList<>();
            for (FileRow row : current) {
                Path jar = rootPath.resolve(row.path).normalize();
                currentKeys.add(new V5ModCatalogMatcher.CurrentMod(
                        row.path, row.modId, Hashing.sha256(jar)));
            }
            V5ModCatalogMatcher.MatchResult matches = V5ModCatalogMatcher.match(currentKeys, imported);
            for (FileRow row : current) {
                ReleaseManifestV5.FileEntry entry = matches.byCurrentPath().get(
                        V5ModCatalogMatcher.normalizePath(row.path));
                if (entry != null) applyImportedV5Metadata(row, entry);
            }

            // The selected client's mods directory is authoritative: stale rows are removed and
            // an entry that only exists in the imported manifest is never resurrected.
            files.rows.removeIf(row -> row.kind.equals("mod"));
            files.rows.addAll(current);
            files.rows.sort(Comparator.comparing(row -> row.path));
            refreshModConflicts();
            files.fireTableDataChanged();
            refreshSummary();
            validation.append("已从 " + manifestPath.getFileName() + " 仅导入 Mods 元数据：按 SHA-256 精确优先、唯一 modId 仅作升级元数据后备，匹配 "
                    + matches.byCurrentPath().size() + "，当前新增 " + matches.newCurrentPaths().size()
                    + "，旧清单已删除/无法唯一对应 " + matches.deletedImportedPaths().size()
                    + "。其他文件、范围、配置操作和远端设置均未改动；正在按当前 JAR 重新匹配下载来源。\n");
            autoMatchMods(null);
        } catch (Exception failure) {
            showError("导入 mods-v5.json 模组信息失败：" + cause(failure).getMessage());
        }
    }

    private static void applyImportedV5Metadata(FileRow row, ReleaseManifestV5.FileEntry entry) {
        row.required = entry.required();
        row.restart = entry.restartRequired();
        row.side = entry.side().stream().sorted().findFirst().orElse("client");
        if (!entry.modId().isBlank()) row.modId = entry.modId();
        if (!entry.displayName().isBlank()) row.displayName = entry.displayName();
        if (row.modVersion.isBlank() && !entry.version().isBlank()) row.modVersion = entry.version();
        if (!entry.descriptionZh().isBlank()) row.descriptionZh = entry.descriptionZh();
        if (!entry.descriptionEn().isBlank()) row.descriptionEn = entry.descriptionEn();
        row.incompatiblePlatforms.clear();
        row.incompatiblePlatforms.addAll(entry.incompatiblePlatforms());
        row.matchDetail = "已从 v5 继承元数据，等待按当前文件重新匹配";
    }

    private void setSelectedModKind(boolean required) {
        int[] selected = modsTable.getSelectedRows();
        for (int viewRow : selected) {
            FileRow row = files.rows.get(modsTable.convertRowIndexToModel(viewRow));
            row.required = required;
        }
        files.fireTableDataChanged();
    }

    private void setSelectedContentRequired(boolean required) {
        int[] selected = fileTable.getSelectedRows();
        for (int viewRow : selected) {
            FileRow row = files.rows.get(fileTable.convertRowIndexToModel(viewRow));
            if (required || Set.of("resource-pack", "shader-pack").contains(row.kind)) {
                row.required = required;
            }
        }
        files.fireTableDataChanged();
    }

    private void editSelectedModMetadata() {
        int viewRow = modsTable.getSelectedRow();
        if (viewRow < 0) {
            showError("请先在 Mods 表格中选择一个模组。");
            return;
        }
        FileRow row = files.rows.get(modsTable.convertRowIndexToModel(viewRow));
        JTextField name = new JTextField(row.displayName, 36);
        JTextField version = new JTextField(row.modVersion, 36);
        JTextArea chinese = new JTextArea(row.descriptionZh, 5, 42);
        JTextArea english = new JTextArea(row.descriptionEn, 5, 42);
        chinese.setLineWrap(true);
        chinese.setWrapStyleWord(true);
        english.setLineWrap(true);
        english.setWrapStyleWord(true);
        JCheckBox windows = new JCheckBox("Windows 不兼容", row.incompatiblePlatforms.contains("windows"));
        JCheckBox linux = new JCheckBox("Linux 不兼容", row.incompatiblePlatforms.contains("linux"));
        JCheckBox macos = new JCheckBox("macOS 不兼容", row.incompatiblePlatforms.contains("macos"));
        JCheckBox android = new JCheckBox("Android 不兼容", row.incompatiblePlatforms.contains("android"));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = constraints();
        addFieldRow(form, c, 0, "显示名称：", name);
        addFieldRow(form, c, 1, "版本：", version);
        c.gridx = 0; c.gridy = 2; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        form.add(new JLabel("中文描述："), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.BOTH;
        form.add(new JScrollPane(chinese), c);
        c.gridx = 0; c.gridy = 3; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        form.add(new JLabel("英文描述："), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.BOTH;
        form.add(new JScrollPane(english), c);
        JPanel platforms = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        platforms.add(windows); platforms.add(linux); platforms.add(macos); platforms.add(android);
        c.gridx = 0; c.gridy = 4; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        form.add(new JLabel("推荐平台限制："), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        form.add(platforms, c);

        int result = JOptionPane.showConfirmDialog(owner, form,
                "编辑 Mod 信息 — " + row.path, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;
        row.displayName = name.getText().strip();
        row.modVersion = version.getText().strip();
        row.descriptionZh = chinese.getText().strip();
        row.descriptionEn = english.getText().strip();
        row.incompatiblePlatforms.clear();
        if (windows.isSelected()) row.incompatiblePlatforms.add("windows");
        if (linux.isSelected()) row.incompatiblePlatforms.add("linux");
        if (macos.isSelected()) row.incompatiblePlatforms.add("macos");
        if (android.isSelected()) row.incompatiblePlatforms.add("android");
        files.fireTableRowsUpdated(modsTable.convertRowIndexToModel(viewRow), modsTable.convertRowIndexToModel(viewRow));
    }

    private static boolean containsHan(String value) {
        return value != null && value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private void autoMatchMods(JButton button) {
        Path rootPath;
        try {
            rootPath = requireGameRoot();
        } catch (IOException failure) {
            showError(failure.getMessage());
            return;
        }
        List<String> conflicts = refreshModConflicts();
        files.fireTableDataChanged();
        if (!conflicts.isEmpty()) {
            validation.append("检测到 " + conflicts.size() + " 组重复 modId；冲突项不会自动匹配，导出保持阻断。\n");
        }
        List<FileRow> mods = files.rows.stream()
                .filter(row -> PublisherModAutoMatcher.isModArtifact(row.path, row.kind))
                .filter(row -> row.conflictDetail.isBlank())
                .toList();
        if (mods.isEmpty()) {
            validation.append("没有需要匹配的 mods/*.jar。\n");
            return;
        }
        if (button != null) button.setEnabled(false);
        validation.append("正在计算哈希、查询平台，并立即下载复核 CurseForge 候选；临时缓存会在每个候选后删除…\n");
        updateModMatchProgress(0, "准备匹配 " + mods.size() + " 个 Mod");
        new SwingWorker<Map<Path, PublisherModAutoMatcher.Match>, Void>() {
            @Override protected Map<Path, PublisherModAutoMatcher.Match> doInBackground() {
                return modMatcher.matchAll(mods.stream().map(row -> rootPath.resolve(row.path)).toList(),
                        V5PublisherWorkspace.this::updateModMatchProgress);
            }
            @Override protected void done() {
                if (button != null) button.setEnabled(true);
                try {
                    Map<Path, PublisherModAutoMatcher.Match> matches = get();
                    int platform = 0;
                    for (FileRow row : mods) {
                        PublisherModAutoMatcher.Match match = matches.get(rootPath.resolve(row.path));
                        if (match == null) match = new PublisherModAutoMatcher.Match(
                                PublisherModAutoMatcher.localDownload(), "未匹配，使用本地文件");
                        row.applyMatch(match);
                        platform += !"publisher-hosted".equals(row.source) ? 1 : 0;
                    }
                    files.fireTableDataChanged();
                    refreshSummary();
                    validation.append("Mod 自动匹配完成：" + platform + " 个上游匹配，"
                            + (mods.size() - platform) + " 个回退本地托管。\n");
                } catch (Exception failure) {
                    showError(cause(failure).getMessage());
                }
            }
        }.execute();
    }

    private void updateModMatchProgress(int percent, String detail) {
        SwingUtilities.invokeLater(() -> {
            int bounded = Math.max(0, Math.min(100, percent));
            modMatchProgress.setValue(bounded);
            modMatchProgress.setString(bounded + "% — " + detail);
        });
    }

    private void addFiles() {
        Path rootPath;
        try {
            rootPath = requireGameRoot();
        } catch (IOException failure) {
            showError(failure.getMessage());
            return;
        }
        JFileChooser chooser = new JFileChooser(rootPath.toFile());
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        Set<String> existing = files.normalizedPaths();
        for (java.io.File selected : chooser.getSelectedFiles()) {
            Path path = selected.toPath().toAbsolutePath().normalize();
            if (!path.startsWith(rootPath)) {
                showError("只能添加游戏根目录内的文件：" + path);
                continue;
            }
            String relative = rootPath.relativize(path).toString().replace('\\', '/');
            if (existing.add(relative.toLowerCase(Locale.ROOT))) {
                FileRow row = FileRow.scanned(relative, kindFor(relative));
                if (PublisherModAutoMatcher.isModArtifact(relative, row.kind)) {
                    populateLocalModMetadata(path, row);
                } else {
                    row.confirmed = true;
                    row.applyLocal("非 Mod 文件固定本地托管");
                }
                files.rows.add(row);
            }
        }
        refreshModConflicts();
        files.rows.sort(Comparator.comparing(row -> row.path));
        files.fireTableDataChanged();
        refreshSummary();
        if (files.rows.stream().anyMatch(row -> PublisherModAutoMatcher.isModArtifact(row.path, row.kind)
                && !row.confirmed)) autoMatchMods(null);
    }

    private static String kindFor(String relative) {
        String normalized = relative.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("mods/")) return "mod";
        if (normalized.startsWith("resourcepacks/")) return "resource-pack";
        if (normalized.startsWith("shaderpacks/")) return "shader-pack";
        if (normalized.startsWith("kubejs/")) return "kubejs";
        if (normalized.startsWith("defaultconfigs/")) return "default-config";
        if (normalized.startsWith("configureddefaults/")) return "default-config";
        if (normalized.startsWith("config/")) return "config";
        return "support";
    }

    private List<String> validateProject() {
        return validateProject(true);
    }

    private List<String> validateProject(boolean deepFileValidation) {
        ArrayList<String> errors = new ArrayList<>();
        List<String> modConflicts = refreshModConflicts();
        for (String conflict : modConflicts) errors.add("Mod 冲突：" + conflict);
        Path rootPath = null;
        try {
            rootPath = requireGameRoot();
        } catch (IOException failure) {
            errors.add(failure.getMessage());
        }
        if (!releaseId.getText().matches("[A-Za-z0-9._-]{1,128}")) errors.add("releaseId 格式无效。");
        if (minimumVersion.getText().isBlank()) errors.add("最低 MCSync 版本不能为空。");
        if (!previousOutputDirectory.getText().isBlank()) {
            try {
                ReleaseManifestV5 previous = readPreviousBaseline();
                long currentSequence = ((Number) releaseSequence.getValue()).longValue();
                if (previous.releaseSequence() >= currentSequence) {
                    errors.add("上一版 releaseSequence 必须小于当前发布序号。");
                }
            } catch (Exception failure) {
                errors.add("上一版发布输出无效：" + failure.getMessage());
            }
        }
        try {
            URI base = URI.create(normalizedBaseUrl());
            if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null) {
                errors.add("公开根地址必须是 HTTPS 绝对地址。");
            }
            validateCloudPath(stableManifestPath.getText(), "2.0 稳定入口", "mods-v5.json");
            validateCloudPath(legacyV4Path.getText(), "1.9.x 入口", "mods-v4.txt");
            validateCloudPath(legacyV2Path.getText(), "1.6.x 入口", "mods.txt");
            if (syncServerList.isSelected()) {
                if (serverListSource.getText().isBlank()) {
                    errors.add("已启用服务器列表同步，但没有选择 servers.dat 源文件。");
                } else {
                    Path source = Path.of(serverListSource.getText()).toAbsolutePath().normalize();
                    if (!Files.isRegularFile(source) || !source.getFileName().toString().equalsIgnoreCase("servers.dat")) {
                        errors.add("服务器列表源必须是普通文件 servers.dat。");
                    } else {
                        ServerListManifest.fromFile(source);
                    }
                }
                validateCloudPath(serverListManifestPath.getText(), "服务器列表清单", "serverlist.txt");
            }
        } catch (Exception failure) {
            errors.add("远端配置无效：" + failure.getMessage());
        }
        if (files.rows.isEmpty()) errors.add("至少需要一个发布文件。");
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < files.rows.size(); i++) {
            FileRow row = files.rows.get(i);
            String at = "文件第 " + (i + 1) + " 行：";
            if (!row.confirmed && row.conflictDetail.isBlank()) errors.add(at + "Mod 自动来源匹配尚未完成。");
            if (row.path.isBlank() || row.path.startsWith("/") || row.path.contains("..")
                    || !unique.add(row.path.toLowerCase(Locale.ROOT))) errors.add(at + "路径为空、不安全或重复。");
            if (rootPath != null && !Files.isRegularFile(rootPath.resolve(row.path), LinkOption.NOFOLLOW_LINKS)) {
                errors.add(at + "本地文件不存在：" + row.path);
            }
            boolean mod = PublisherModAutoMatcher.isModArtifact(row.path, row.kind);
            boolean selectable = Set.of("mod", "resource-pack", "shader-pack").contains(row.kind);
            if (!row.required && !selectable) {
                errors.add(at + "只有 Mod、资源包和光影包可以设为可选；其他玩法文件必须同步。");
            }
            if (!mod && !row.source.equals("publisher-hosted")) {
                errors.add(at + "非 Mod 文件禁止使用模组站、镜像或 direct/manual 来源。");
            }
            if (!mod && !row.policy.equals("redistributable")) {
                errors.add(at + "非 Mod 文件固定使用本地托管策略。");
            }
            if (mod && row.source.equals("modrinth") && (row.projectId.isBlank() || row.versionId.isBlank())) {
                errors.add(at + "Modrinth 匹配缺少固定项目/版本。");
            }
            if (mod && row.source.equals("curseforge") && (row.projectId.isBlank() || !row.fileId.matches("[1-9][0-9]*"))) {
                errors.add(at + "CurseForge 匹配缺少固定项目/文件。");
            }
        }
        try {
            Map<String, Object> project = projectMap();
            StrictJson.parse(StrictJson.stringify(project));
            if (deepFileValidation && rootPath != null) PublisherProjectV5.validateProject(rootPath, project);
        } catch (Exception failure) {
            errors.add("项目结构无效：" + failure.getMessage());
        }
        return errors;
    }

    private void showValidation() {
        List<String> errors = validateProject();
        if (errors.isEmpty()) {
            validation.setText("PASS：项目的本地路径、来源、分发策略与配置操作已通过 GUI 预检。\n"
                    + "导出时还会计算 SHA-256，通过严格 schema-v5 解析，并解析平台固定版本地址。\n");
        } else {
            StringBuilder text = new StringBuilder("BLOCKED：发现 " + errors.size() + " 个问题\n\n");
            for (String error : errors) text.append(" - ").append(error).append('\n');
            validation.setText(text.toString());
        }
        validation.setCaretPosition(0);
    }

    private void publish(JButton button) {
        if (autoReleaseSequence.isSelected()) {
            releaseSequence.setValue(PublisherProjectV5.nextReleaseSequence(
                    ((Number) releaseSequence.getValue()).longValue()));
        }
        List<String> errors = validateProject(false);
        if (!errors.isEmpty()) {
            StringBuilder text = new StringBuilder("BLOCKED：发现 " + errors.size() + " 个问题\n\n");
            for (String error : errors) text.append(" - ").append(error).append('\n');
            validation.setText(text.toString());
            JOptionPane.showMessageDialog(owner, "请先修复验证问题。", "发布被阻止", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path rootPath;
        Path output;
        try {
            rootPath = requireGameRoot();
            if (outputDirectory.getText().isBlank()) throw new IOException("请选择发布输出目录。");
            output = Path.of(outputDirectory.getText()).toAbsolutePath().normalize();
        } catch (Exception failure) {
            showError(failure.getMessage());
            return;
        }
        Map<String, Object> project;
        try {
            project = projectMap();
        } catch (Exception failure) {
            showError(failure.getMessage());
            return;
        }
        button.setEnabled(false);
        publishProgress.setValue(0);
        publishProgress.setString("准备发布 0%");
        publishProgressDetail.setText("检查发布参数…");
        validation.append("\n开始计算哈希、解析平台来源并生成发布目录…\n");
        new SwingWorker<PublisherCloudBundle.Result, PublisherProgress.Update>() {
            @Override
            protected PublisherCloudBundle.Result doInBackground() throws Exception {
                Path updater = generateLegacyGateways.isSelected() ? locateUpdaterJar(rootPath) : null;
                ReleaseManifestV5 previous = readPreviousBaseline();
                return PublisherCloudBundle.publish(
                        rootPath, project, output, normalizedBaseUrl(), stableManifestPath.getText(),
                        legacyV4Path.getText(), legacyV2Path.getText(),
                        syncServerList.isSelected() ? Path.of(serverListSource.getText()).toAbsolutePath().normalize() : null,
                        syncServerList.isSelected() ? serverListManifestPath.getText() : "",
                        generateLegacyGateways.isSelected(), updater, previous, this::publish);
            }

            @Override
            protected void process(List<PublisherProgress.Update> updates) {
                if (!updates.isEmpty()) applyPublishProgress(updates.getLast());
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                try {
                    PublisherCloudBundle.Result cloud = get();
                    publishProgress.setValue(100);
                    publishProgress.setString("发布完成 100%");
                    publishProgressDetail.setText("发布目录、清单与增量上传指南均已生成");
                    PublisherProjectV5.Publication publication = cloud.publication();
                    validation.append("发布完成：" + publication.manifestPath() + "\n"
                            + "托管文件：" + publication.hostedFiles() + "\n"
                            + "复用上一版：" + publication.reusedHostedFiles() + "\n"
                            + "复用平台验证：" + publication.reusedPlatformVerifications() + "\n"
                            + "报告：" + publication.reportPath() + "\n"
                            + "增量上传指南：" + cloud.uploadGuideZh() + "\n"
                            + "机器上传计划：" + cloud.uploadPlan() + "\n"
                            + "2.0 稳定入口：" + stableUrl() + "\n");
                    JOptionPane.showMessageDialog(owner,
                            "schema-v5 OTA 发布已生成。\n" + publication.manifestPath(),
                            "发布完成", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception failure) {
                    Throwable actual = cause(failure);
                    publishProgress.setString("发布失败");
                    publishProgressDetail.setText(actual.getMessage());
                    validation.append("发布失败：" + actual.getMessage() + "\n");
                    showError(actual.getMessage());
                }
            }
        }.execute();
    }

    private void applyPublishProgress(PublisherProgress.Update update) {
        int start;
        int span;
        String label;
        switch (update.stage()) {
            case PREPARE -> { start = 0; span = 3; label = "准备"; }
            case HASH_AND_PLATFORM -> { start = 3; span = 72; label = "哈希与平台核验"; }
            case COPY_HOSTED -> { start = 75; span = 15; label = "复制托管文件"; }
            case WRITE_MANIFEST -> { start = 90; span = 4; label = "生成清单"; }
            case BUILD_CLOUD_BUNDLE -> { start = 94; span = 5; label = "整理云端发布包"; }
            case COMPLETE -> { start = 100; span = 0; label = "完成"; }
            default -> throw new IllegalStateException("unknown publisher stage: " + update.stage());
        }
        int percent = update.total() == 0 ? start + span
                : start + (int) Math.floor((double) update.completed() * span / update.total());
        percent = Math.max(0, Math.min(100, percent));
        publishProgress.setValue(percent);
        String counts = update.total() > 1 ? " " + update.completed() + "/" + update.total() : "";
        publishProgress.setString(label + counts + " · " + percent + "%");
        String detail = update.detail().isBlank() ? label : update.detail();
        publishProgressDetail.setText(detail.length() <= 150 ? detail : "…" + detail.substring(detail.length() - 149));
        publishProgressDetail.setToolTipText(detail);
    }

    private void saveProject() {
        try {
            validation.append("正在打开系统项目保存对话框…\n");
            Path output = chooseProjectFile(FileDialog.SAVE);
            if (output == null) {
                validation.append("已取消保存发布项目。\n");
                return;
            }
            if (output.getParent() != null) Files.createDirectories(output.getParent());
            Files.writeString(output, StrictJson.stringify(projectMap()) + "\n", StandardCharsets.UTF_8);
            projectFile = output;
            validation.append("项目已保存：" + output + "\n");
        } catch (Exception failure) {
            showError(failure.getMessage());
        }
    }

    private void loadProject() {
        try {
            validation.append("正在打开系统项目选择器…\n");
            Path selected = chooseProjectFile(FileDialog.LOAD);
            if (selected == null) {
                validation.append("已取消打开发布项目。\n");
                return;
            }
            validation.append("正在读取发布项目：" + selected + "\n");
            Object parsed = StrictJson.parse(Files.readString(selected, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> raw)) throw new IOException("项目根必须是 JSON 对象。");
            @SuppressWarnings("unchecked") Map<String, Object> project = (Map<String, Object>) raw;
            loadProjectMap(project);
            projectFile = selected;
            // Opening an older project means preparing a new publication. Never reuse its
            // release sequence, even when the saved project had auto-refresh disabled.
            autoReleaseSequence.setSelected(true);
            releaseSequence.setValue(PublisherProjectV5.nextReleaseSequence(
                    ((Number) releaseSequence.getValue()).longValue()));
            validation.append("项目已加载：" + selected + "\n");
            if (gameRoot.getText().isBlank()) {
                validation.append("旧项目没有保存客户端根目录；请在“发布项目”页重新选择。\n");
            }
            validation.setCaretPosition(validation.getDocument().getLength());
            workspaceTabs.setSelectedIndex(files.rows.isEmpty() ? 0 : 1);
            JOptionPane.showMessageDialog(owner,
                    "发布项目已加载。\n文件：" + files.rows.size()
                            + "\n同步范围：" + scopes.rows.size()
                            + "\n配置操作：" + config.rows.size()
                            + (gameRoot.getText().isBlank() ? "\n\n此旧项目未保存客户端目录，请重新选择。" : ""),
                    "MCSync 2.0 发布项目", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception failure) {
            showError("无法加载项目：" + failure.getMessage());
        }
    }

    private void importExistingV5Manifest() {
        try {
            validation.append("正在选择现有 mods-v5.json…\n");
            Path selected = chooseExistingV5File();
            if (selected == null) {
                validation.append("已取消从现有 v5 清单继续发布。\n");
                return;
            }
            byte[] bytes = Files.readAllBytes(selected);
            ReleaseManifestV5.parse(bytes); // strict schema, path, source and policy validation
            Object parsed = StrictJson.parse(new String(bytes, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> raw)) throw new IOException("v5 清单根必须是 JSON 对象。");
            @SuppressWarnings("unchecked") Map<String, Object> manifest = (Map<String, Object>) raw;
            Map<String, Object> project = continuationProject(manifest);
            loadProjectMap(project);
            autoReleaseSequence.setSelected(true);
            releaseSequence.setValue(PublisherProjectV5.nextReleaseSequence(
                    ((Number) releaseSequence.getValue()).longValue()));
            projectFile = null;
            validation.append("已继承现有 v5 清单：" + selected + "\n"
                    + "已保留双语描述、必须/可选、同步范围和配置 OTA；导出时将重新计算文件哈希与时间序号。\n");
            validation.setCaretPosition(validation.getDocument().getLength());
            workspaceTabs.setSelectedIndex(1);
            JOptionPane.showMessageDialog(owner,
                    "现有 mods-v5.json 已导入为下一版发布基线。\n"
                            + "文件：" + files.rows.size() + "\n"
                            + "配置操作：" + config.rows.size() + "\n\n"
                            + "请确认客户端根目录，然后扫描/匹配新加入或改名的文件并保存发布项目。",
                    "继续发布", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception failure) {
            showError("无法导入现有 mods-v5.json：" + cause(failure).getMessage());
        }
    }

    private Path chooseExistingV5File() {
        FileDialog dialog = new FileDialog(owner, "选择现有 mods-v5.json", FileDialog.LOAD);
        dialog.setMultipleMode(false);
        dialog.setFilenameFilter((directory, name) -> name.equalsIgnoreCase("mods-v5.json")
                || name.toLowerCase(Locale.ROOT).endsWith(".json"));
        dialog.setDirectory(projectFile != null && projectFile.getParent() != null
                ? projectFile.getParent().toString()
                : System.getProperty("user.dir", "."));
        dialog.setFile("mods-v5.json");
        dialog.setVisible(true);
        String name = dialog.getFile();
        String directory = dialog.getDirectory();
        dialog.dispose();
        owner.toFront();
        owner.requestFocus();
        return name == null || directory == null ? null : Path.of(directory, name).toAbsolutePath().normalize();
    }

    private ReleaseManifestV5 readPreviousBaseline() throws IOException {
        return previousOutputDirectory.getText().isBlank()
                ? null : PublisherOutputBaseline.read(Path.of(previousOutputDirectory.getText()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> continuationProject(Map<String, Object> manifest) throws IOException {
        LinkedHashMap<String, Object> project = new LinkedHashMap<>();
        project.put("schema", BigDecimal.ONE);
        project.put("releaseId", textOrEmpty(manifest.get("releaseId")));
        project.put("releaseSequence", manifest.getOrDefault("releaseSequence", BigDecimal.ONE));
        project.put("minimumMCSyncVersion", textOrEmpty(manifest.get("minimumMCSyncVersion")));
        LinkedHashMap<String, Object> remote = new LinkedHashMap<>();
        remote.put("baseUrl", publicBaseUrl.getText().strip());
        remote.put("stablePath", cloudPath(stableManifestPath.getText()));
        remote.put("legacyV4Path", cloudPath(legacyV4Path.getText()));
        remote.put("legacyV2Path", cloudPath(legacyV2Path.getText()));
        remote.put("syncServerList", syncServerList.isSelected());
        remote.put("serverListSource", serverListSource.getText().strip());
        remote.put("serverListManifestPath", cloudPath(serverListManifestPath.getText()));
        remote.put("gameRoot", gameRoot.getText().strip());
        remote.put("outputDirectory", outputDirectory.getText().strip());
        remote.put("previousOutputDirectory", previousOutputDirectory.getText().strip());
        remote.put("autoReleaseSequence", true);
        remote.put("generateLegacyGateways", generateLegacyGateways.isSelected());
        project.put("remote", remote);
        project.put("managedScopes", manifest.getOrDefault("managedScopes", List.of()));
        project.put("configOperations", manifest.getOrDefault("configOperations", List.of()));

        Path root = null;
        if (!gameRoot.getText().isBlank()) {
            Path candidate = Path.of(gameRoot.getText()).toAbsolutePath().normalize();
            if (Files.isDirectory(candidate)) root = candidate;
        }
        Map<String, String> currentModsById = currentModPathsById(root);
        List<Object> importedFiles = new ArrayList<>();
        for (Object value : (List<Object>) manifest.getOrDefault("files", List.of())) {
            if (!(value instanceof Map<?, ?> rawFile)) continue;
            LinkedHashMap<String, Object> file = new LinkedHashMap<>((Map<String, Object>) rawFile);
            file.remove("sha256");
            file.remove("size");
            String path = textOrEmpty(file.get("path"));
            if (root != null && !Files.isRegularFile(root.resolve(path), LinkOption.NOFOLLOW_LINKS)
                    && "mod".equals(textOrEmpty(file.get("kind")))) {
                String replacement = currentModsById.get(textOrEmpty(file.get("modId")).toLowerCase(Locale.ROOT));
                if (replacement != null) file.put("path", replacement);
            }
            importedFiles.add(file);
        }
        project.put("files", importedFiles);
        return project;
    }

    private static Map<String, String> currentModPathsById(Path root) throws IOException {
        if (root == null || !Files.isDirectory(root.resolve("mods"))) return Map.of();
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        HashSet<String> ambiguous = new HashSet<>();
        try (var stream = Files.list(root.resolve("mods"))) {
            for (Path jar : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted().toList()) {
                String modId = ModMetadata.readModId(jar).toLowerCase(Locale.ROOT);
                if (modId.isBlank()) continue;
                String relative = root.relativize(jar).toString().replace('\\', '/');
                if (result.putIfAbsent(modId, relative) != null) ambiguous.add(modId);
            }
        }
        ambiguous.forEach(result::remove);
        return result;
    }

    private static String textOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Path chooseProjectFile(int mode) {
        String title = mode == FileDialog.LOAD
                ? "打开 MCSync 2.0 发布项目"
                : "保存 MCSync 2.0 发布项目";
        FileDialog dialog = new FileDialog(owner, title, mode);
        dialog.setMultipleMode(false);
        dialog.setFilenameFilter((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (projectFile != null) {
            Path parent = projectFile.getParent();
            if (parent != null) dialog.setDirectory(parent.toString());
            dialog.setFile(projectFile.getFileName().toString());
        } else {
            dialog.setDirectory(System.getProperty("user.dir", "."));
            if (mode == FileDialog.SAVE) dialog.setFile(releaseId.getText().strip() + ".publisher.json");
        }
        dialog.setVisible(true);
        String selectedName = dialog.getFile();
        String selectedDirectory = dialog.getDirectory();
        dialog.dispose();
        owner.toFront();
        owner.requestFocus();
        if (selectedName == null || selectedDirectory == null) return null;
        Path selected = Path.of(selectedDirectory, selectedName).toAbsolutePath().normalize();
        if (mode == FileDialog.SAVE && !selected.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
            selected = selected.resolveSibling(selected.getFileName() + ".publisher.json");
        }
        return selected;
    }

    private Map<String, Object> projectMap() {
        LinkedHashMap<String, Object> project = new LinkedHashMap<>();
        project.put("schema", BigDecimal.ONE);
        project.put("releaseId", releaseId.getText().strip());
        project.put("releaseSequence", BigDecimal.valueOf(((Number) releaseSequence.getValue()).longValue()));
        project.put("minimumMCSyncVersion", minimumVersion.getText().strip());
        LinkedHashMap<String, Object> remote = new LinkedHashMap<>();
        remote.put("baseUrl", normalizedBaseUrl());
        remote.put("stablePath", cloudPath(stableManifestPath.getText()));
        remote.put("legacyV4Path", cloudPath(legacyV4Path.getText()));
        remote.put("legacyV2Path", cloudPath(legacyV2Path.getText()));
        remote.put("syncServerList", syncServerList.isSelected());
        remote.put("serverListSource", serverListSource.getText().strip());
        remote.put("serverListManifestPath", cloudPath(serverListManifestPath.getText()));
        remote.put("gameRoot", gameRoot.getText().strip());
        remote.put("outputDirectory", outputDirectory.getText().strip());
        remote.put("previousOutputDirectory", previousOutputDirectory.getText().strip());
        remote.put("autoReleaseSequence", autoReleaseSequence.isSelected());
        remote.put("generateLegacyGateways", generateLegacyGateways.isSelected());
        project.put("remote", remote);
        project.put("managedScopes", scopes.rows.stream().map(row -> Map.of(
                "path", row.path.strip(), "policy", row.policy)).toList());
        project.put("files", files.rows.stream().map(this::fileJson).toList());
        project.put("configOperations", config.rows.stream().map(this::configJson).toList());
        return project;
    }

    private Path locateUpdaterJar(Path rootPath) throws IOException {
        try {
            URI location = V5PublisherWorkspace.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path running = Path.of(location).toAbsolutePath().normalize();
            if (Files.isRegularFile(running) && running.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return running;
            }
        } catch (Exception ignored) {
        }
        Path mods = rootPath.resolve("mods");
        if (!Files.isDirectory(mods)) throw new IOException("游戏根目录缺少 mods，无法生成旧版升级入口。");
        ModManifest scanned = ModManifest.scan(mods);
        ManifestEntry updater = scanned.entries().stream().filter(entry -> entry.modId().equals("mcmodsync"))
                .findFirst().orElseThrow(() -> new IOException("找不到 MCSync/MCModSync 升级器 JAR。"));
        return mods.resolve(updater.fileName());
    }

    private String stableUrl() {
        return normalizedBaseUrl() + "/" + cloudPath(stableManifestPath.getText());
    }

    private String normalizedBaseUrl() {
        String value = publicBaseUrl.getText().strip();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String cloudPath(String value) {
        String normalized = value.strip().replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }

    private static void validateCloudPath(String value, String label, String fileName) {
        String normalized = cloudPath(value);
        if (normalized.isBlank() || normalized.contains("..") || normalized.contains(":")
                || !normalized.endsWith("/" + fileName)) {
            throw new IllegalArgumentException(label + " 必须是以 /" + fileName + " 结尾的安全相对路径");
        }
    }

    private Map<String, Object> fileJson(FileRow row) {
        LinkedHashMap<String, Object> file = new LinkedHashMap<>();
        file.put("path", row.path.strip().replace('\\', '/'));
        file.put("kind", row.kind);
        file.put("required", row.required);
        file.put("restartRequired", row.restart);
        file.put("side", List.of(row.side));
        if (Set.of("mod", "resource-pack", "shader-pack").contains(row.kind)) {
            if (!row.modId.isBlank()) file.put("modId", row.modId.strip());
            if (!row.displayName.isBlank()) file.put("displayName", row.displayName.strip());
            if (!row.modVersion.isBlank()) file.put("version", row.modVersion.strip());
            if (!row.descriptionZh.isBlank()) file.put("descriptionZh", row.descriptionZh.strip());
            if (!row.descriptionEn.isBlank()) file.put("descriptionEn", row.descriptionEn.strip());
            if (!row.incompatiblePlatforms.isEmpty()) {
                file.put("incompatiblePlatforms", row.incompatiblePlatforms.stream().sorted().toList());
            }
        }
        LinkedHashMap<String, Object> download = new LinkedHashMap<>();
        download.put("type", row.source);
        download.put("distributionPolicy", row.policy);
        if (!row.projectId.isBlank()) download.put("projectId", row.projectId.strip());
        if (!row.versionId.isBlank()) download.put("versionId", row.versionId.strip());
        if (row.source.equals("curseforge") && !row.fileId.isBlank()) {
            download.put("fileId", new BigDecimal(row.fileId.strip()));
        }
        if (row.source.equals("modrinth") || row.source.equals("curseforge")) {
            List<ReleaseManifestV5.DownloadEndpoint> endpoints = DownloadEndpointPresets.forPlatform(row.source, row.chinaMirror);
            download.put("endpoints", endpoints.stream().map(endpoint -> Map.of(
                    "url", endpoint.uri().toASCIIString(), "role", endpoint.role(),
                    "purpose", endpoint.purpose(), "region", endpoint.region(),
                    "priority", endpoint.priority(), "thirdParty", endpoint.thirdParty())).toList());
        } else if (row.source.equals("direct")) {
            download.put("endpoints", List.of(Map.of(
                    "url", row.directUrl.strip(), "role", "official", "purpose", "file",
                    "region", "global", "priority", 100, "thirdParty", false)));
        }
        file.put("download", download);
        return file;
    }

    private Map<String, Object> configJson(ConfigRow row) {
        LinkedHashMap<String, Object> operation = new LinkedHashMap<>();
        operation.put("path", row.path.strip().replace('\\', '/'));
        operation.put("op", row.operation);
        operation.put("format", row.format);
        if (!row.operation.equals("file-replace")) operation.put("key", row.key.strip());
        operation.put("valueType", row.valueType);
        operation.put("expected", parseValue(row.expected, row.valueType));
        operation.put("desired", parseValue(row.desired, row.valueType));
        if (!row.expectedSha256.isBlank()) operation.put("expectedSha256", row.expectedSha256.strip());
        operation.put("missingPolicy", row.missingPolicy);
        operation.put("conflictPolicy", row.conflictPolicy);
        operation.put("side", List.of(row.side));
        operation.put("phase", row.phase);
        operation.put("restartRequired", row.restart);
        return operation;
    }

    private static Object parseValue(String text, String type) {
        String stripped = text == null ? "" : text.strip();
        if (type.equals("string") || type.equals("binary")) return stripped;
        if (stripped.isEmpty()) return null;
        return StrictJson.parse(stripped);
    }

    @SuppressWarnings("unchecked")
    private void loadProjectMap(Map<String, Object> project) throws IOException {
        if (!BigDecimal.ONE.equals(project.get("schema"))) throw new IOException("只能打开 schema=1 的发布项目。");
        releaseId.setText(String.valueOf(project.getOrDefault("releaseId", "")));
        releaseSequence.setValue(((BigDecimal) project.getOrDefault("releaseSequence", BigDecimal.ONE)).longValue());
        minimumVersion.setText(String.valueOf(project.getOrDefault("minimumMCSyncVersion", BuildInfo.VERSION)));
        Map<String, Object> remote = (Map<String, Object>) project.getOrDefault("remote", Map.of());
        publicBaseUrl.setText(String.valueOf(remote.getOrDefault("baseUrl", publicBaseUrl.getText())));
        stableManifestPath.setText(String.valueOf(remote.getOrDefault("stablePath", stableManifestPath.getText())));
        legacyV4Path.setText(String.valueOf(remote.getOrDefault("legacyV4Path", legacyV4Path.getText())));
        legacyV2Path.setText(String.valueOf(remote.getOrDefault("legacyV2Path", legacyV2Path.getText())));
        boolean savedServerList = Boolean.TRUE.equals(remote.get("syncServerList"));
        syncServerList.setSelected(savedServerList);
        serverListSource.setText(String.valueOf(remote.getOrDefault("serverListSource", "")));
        serverListManifestPath.setText(String.valueOf(remote.getOrDefault(
                "serverListManifestPath", serverListManifestPath.getText())));
        gameRoot.setText(String.valueOf(remote.getOrDefault("gameRoot", gameRoot.getText())));
        outputDirectory.setText(String.valueOf(remote.getOrDefault("outputDirectory", outputDirectory.getText())));
        previousOutputDirectory.setText(String.valueOf(remote.getOrDefault("previousOutputDirectory", "")));
        autoReleaseSequence.setSelected(!Boolean.FALSE.equals(remote.get("autoReleaseSequence")));
        generateLegacyGateways.setSelected(!Boolean.FALSE.equals(remote.get("generateLegacyGateways")));
        scopes.rows.clear();
        for (Object value : (List<Object>) project.getOrDefault("managedScopes", List.of())) {
            Map<String, Object> row = (Map<String, Object>) value;
            scopes.rows.add(new ScopeRow(String.valueOf(row.get("path")), String.valueOf(row.get("policy"))));
        }
        files.rows.clear();
        for (Object value : (List<Object>) project.getOrDefault("files", List.of())) {
            Map<String, Object> row = (Map<String, Object>) value;
            Map<String, Object> download = (Map<String, Object>) row.getOrDefault("download", Map.of());
            List<Object> side = (List<Object>) row.getOrDefault("side", List.of("client"));
            FileRow file = FileRow.scanned(String.valueOf(row.get("path")), String.valueOf(row.get("kind")));
            file.confirmed = true;
            file.required = Boolean.TRUE.equals(row.get("required"));
            file.restart = !Boolean.FALSE.equals(row.get("restartRequired"));
            file.side = side.isEmpty() ? "client" : String.valueOf(side.getFirst());
            file.modId = textOrEmpty(row.get("modId"));
            file.displayName = textOrEmpty(row.get("displayName"));
            file.modVersion = textOrEmpty(row.get("version"));
            file.descriptionZh = textOrEmpty(row.get("descriptionZh"));
            file.descriptionEn = textOrEmpty(row.get("descriptionEn"));
            for (Object platform : (List<Object>) row.getOrDefault("incompatiblePlatforms", List.of())) {
                file.incompatiblePlatforms.add(String.valueOf(platform));
            }
            file.source = String.valueOf(download.getOrDefault("type", "publisher-hosted"));
            file.policy = String.valueOf(download.getOrDefault("distributionPolicy", defaultPolicy(file.source)));
            file.projectId = String.valueOf(download.getOrDefault("projectId", ""));
            file.versionId = String.valueOf(download.getOrDefault("versionId", ""));
            file.fileId = download.containsKey("fileId") ? String.valueOf(((BigDecimal) download.get("fileId")).longValue()) : "";
            List<Object> endpoints = (List<Object>) download.getOrDefault("endpoints", List.of());
            file.chinaMirror = endpoints.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .anyMatch(endpoint -> "cn".equals(endpoint.get("region")));
            file.directUrl = endpoints.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .filter(endpoint -> "file".equals(endpoint.get("purpose"))).map(endpoint -> String.valueOf(endpoint.get("url")))
                    .findFirst().orElse("");
            if (!PublisherModAutoMatcher.isModArtifact(file.path, file.kind)) {
                file.applyLocal("非 Mod 文件固定本地托管");
            }
            files.rows.add(file);
        }
        config.rows.clear();
        for (Object value : (List<Object>) project.getOrDefault("configOperations", List.of())) {
            Map<String, Object> row = (Map<String, Object>) value;
            ConfigRow item = ConfigRow.defaults();
            item.path = String.valueOf(row.getOrDefault("path", ""));
            item.operation = String.valueOf(row.getOrDefault("op", "config-set"));
            item.format = String.valueOf(row.getOrDefault("format", "toml"));
            item.key = String.valueOf(row.getOrDefault("key", ""));
            item.valueType = String.valueOf(row.getOrDefault("valueType", "string"));
            item.expected = displayValue(row.get("expected"), item.valueType);
            item.desired = displayValue(row.get("desired"), item.valueType);
            item.expectedSha256 = String.valueOf(row.getOrDefault("expectedSha256", ""));
            item.missingPolicy = String.valueOf(row.getOrDefault("missingPolicy", "block"));
            item.conflictPolicy = String.valueOf(row.getOrDefault("conflictPolicy", "block"));
            List<Object> side = (List<Object>) row.getOrDefault("side", List.of("both"));
            item.side = side.isEmpty() ? "both" : String.valueOf(side.getFirst());
            item.phase = String.valueOf(row.getOrDefault("phase", "prelaunch"));
            item.restart = !Boolean.FALSE.equals(row.get("restartRequired"));
            config.rows.add(item);
        }
        scopes.fireTableDataChanged();
        refreshModConflicts();
        files.fireTableDataChanged();
        config.fireTableDataChanged();
        refreshSummary();
    }

    private static String displayValue(Object value, String type) {
        if (value == null) return "";
        if (type.equals("string") || type.equals("binary")) return String.valueOf(value);
        return StrictJson.stringify(value);
    }

    private Path requireGameRoot() throws IOException {
        if (gameRoot.getText().isBlank()) throw new IOException("请选择游戏根目录。");
        Path rootPath = Path.of(gameRoot.getText()).toAbsolutePath().normalize();
        if (!Files.isDirectory(rootPath)) throw new IOException("游戏根目录不存在：" + rootPath);
        return rootPath;
    }

    private void refreshSummary() {
        long confirmed = files.rows.stream().filter(row -> row.confirmed).count();
        long conflicts = files.rows.stream().filter(row -> !row.conflictDetail.isBlank()).count();
        summary.setText("文件 " + files.rows.size() + " / 已确认 " + confirmed
                + " / 冲突 " + conflicts + "  ·  范围 " + scopes.rows.size()
                + "  ·  配置操作 " + config.rows.size());
    }

    private List<String> refreshModConflicts() {
        LinkedHashMap<String, List<FileRow>> byId = new LinkedHashMap<>();
        for (FileRow row : files.rows) {
            if (!row.kind.equals("mod")) continue;
            if (!row.conflictDetail.isBlank()) {
                row.conflictDetail = "";
                row.confirmed = false;
                row.matchDetail = "冲突已解除，等待重新匹配";
            }
            String id = row.modId.strip().toLowerCase(Locale.ROOT);
            if (!id.isBlank()) byId.computeIfAbsent(id, ignored -> new ArrayList<>()).add(row);
        }
        ArrayList<String> conflicts = new ArrayList<>();
        byId.forEach((id, rows) -> {
            if (rows.size() < 2) return;
            String detail = "同一 modId=" + id + " 检测到多个 JAR：" + rows.stream()
                    .map(row -> row.path + (row.modVersion.isBlank() ? "" : " (" + row.modVersion + ")"))
                    .sorted()
                    .reduce((left, right) -> left + "；" + right)
                    .orElse("");
            conflicts.add(detail);
            for (FileRow row : rows) row.markConflict(detail);
        });
        return List.copyOf(conflicts);
    }

    private void showModConflictWarning() {
        List<String> conflicts = refreshModConflicts();
        if (conflicts.isEmpty()) return;
        StringBuilder message = new StringBuilder("检测到重复模组版本，已阻止自动选择和导出：\n\n");
        for (String conflict : conflicts.stream().limit(8).toList()) {
            message.append("• ").append(conflict).append('\n');
        }
        if (conflicts.size() > 8) message.append("…其余 ").append(conflicts.size() - 8).append(" 组请在 Mods 表格中查看。\n");
        message.append("\n请移除旧版或重复 JAR 后重新扫描。");
        JOptionPane.showMessageDialog(owner, message.toString(), "MCSync Mod 冲突",
                JOptionPane.WARNING_MESSAGE);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(owner, message, "MCSync 2.0 发布器", JOptionPane.ERROR_MESSAGE);
    }

    private static Throwable cause(Throwable failure) {
        return failure.getCause() == null ? failure : failure.getCause();
    }

    private static void configureCombo(JTable table, int column, String[] values) {
        TableColumn target = table.getColumnModel().getColumn(column);
        target.setCellEditor(new javax.swing.DefaultCellEditor(new JComboBox<>(values)));
    }

    private static <T> void removeSelected(JTable table, List<T> rows) {
        int[] selected = table.getSelectedRows();
        for (int index = selected.length - 1; index >= 0; index--) {
            rows.remove(table.convertRowIndexToModel(selected[index]));
        }
        ((AbstractTableModel) table.getModel()).fireTableDataChanged();
    }

    private static String defaultPolicy(String source) {
        return source.equals("publisher-hosted") ? "redistributable" : source.equals("manual") ? "manual" : "upstream-only";
    }

    private record ModScanResult(int inherited, int replaced, int added, int removed, int conflicts) {
    }

    private static final class FileRow {
        boolean confirmed;
        String path;
        String kind;
        boolean required = true;
        boolean restart = true;
        String side = "client";
        String source = "publisher-hosted";
        String policy = "redistributable";
        String projectId = "";
        String versionId = "";
        String fileId = "";
        String directUrl = "";
        boolean chinaMirror = true;
        String matchDetail = "待匹配";
        String modId = "";
        String displayName = "";
        String modVersion = "";
        String descriptionZh = "";
        String descriptionEn = "";
        String conflictDetail = "";
        final Set<String> incompatiblePlatforms = new HashSet<>();

        static FileRow scanned(String path, String kind) {
            FileRow row = new FileRow();
            row.path = path;
            row.kind = kind;
            if (Set.of("resource-pack", "shader-pack").contains(kind)) {
                row.displayName = Path.of(path).getFileName().toString();
            }
            row.restart = kind.equals("mod") || kind.equals("kubejs")
                    || kind.equals("config") || kind.equals("default-config");
            return row;
        }

        void applyLocal(String detail) {
            source = "publisher-hosted";
            policy = "redistributable";
            projectId = "";
            versionId = "";
            fileId = "";
            directUrl = "";
            chinaMirror = false;
            matchDetail = detail;
        }

        void applyMatch(PublisherModAutoMatcher.Match match) {
            if (!conflictDetail.isBlank()) return;
            Map<String, Object> download = match.download();
            source = String.valueOf(download.getOrDefault("type", "publisher-hosted"));
            policy = String.valueOf(download.getOrDefault("distributionPolicy", "redistributable"));
            projectId = String.valueOf(download.getOrDefault("projectId", ""));
            versionId = String.valueOf(download.getOrDefault("versionId", ""));
            Object id = download.get("fileId");
            fileId = id == null ? "" : String.valueOf(id);
            matchDetail = match.detail();
            if (displayName.isBlank() && !match.displayName().isBlank()) {
                displayName = match.displayName();
            }
            if (descriptionEn.isBlank() && !match.descriptionEn().isBlank()) {
                descriptionEn = match.descriptionEn();
            }
            confirmed = true;
        }

        void resetDownloadMatch(String detail) {
            confirmed = false;
            source = "publisher-hosted";
            policy = "redistributable";
            projectId = "";
            versionId = "";
            fileId = "";
            directUrl = "";
            matchDetail = detail;
            conflictDetail = "";
        }

        void markConflict(String detail) {
            conflictDetail = detail;
            confirmed = false;
            matchDetail = "冲突：" + detail;
        }
    }

    private static final class FileModel extends AbstractTableModel {
        private final String[] columns = {
                "状态", "相对路径", "类型", "必须", "推荐/可选", "重启", "作用端", "获取方式", "匹配结果"};
        final List<FileRow> rows = new ArrayList<>();

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) { return Set.of(3, 4, 5).contains(column) ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int row, int column) {
            if (column == 4) return Set.of("mod", "resource-pack", "shader-pack").contains(rows.get(row).kind);
            if (column == 3) return Set.of("mod", "resource-pack", "shader-pack").contains(rows.get(row).kind);
            return column == 5 || column == 6;
        }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            FileRow r = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> !r.conflictDetail.isBlank() ? "冲突" : r.confirmed ? "已确定" : "待匹配";
                case 1 -> r.path; case 2 -> r.kind;
                case 3 -> r.required; case 4 -> Set.of("mod", "resource-pack", "shader-pack").contains(r.kind) && !r.required;
                case 5 -> r.restart; case 6 -> r.side;
                case 7 -> r.source.equals("publisher-hosted") ? "本地托管" : "上游平台";
                case 8 -> r.matchDetail; default -> "";
            };
        }
        @Override public void setValueAt(Object value, int rowIndex, int columnIndex) {
            FileRow r = rows.get(rowIndex);
            switch (columnIndex) {
                case 3 -> r.required = Boolean.TRUE.equals(value);
                case 4 -> {
                    if (Set.of("mod", "resource-pack", "shader-pack").contains(r.kind)) {
                        r.required = !Boolean.TRUE.equals(value);
                    }
                }
                case 5 -> r.restart = Boolean.TRUE.equals(value); case 6 -> r.side = String.valueOf(value);
                default -> { }
            }
            fireTableRowsUpdated(rowIndex, rowIndex);
        }
        Set<String> normalizedPaths() {
            HashSet<String> values = new HashSet<>();
            for (FileRow row : rows) values.add(row.path.toLowerCase(Locale.ROOT));
            return values;
        }
    }

    private static final class ScopeRow {
        String path;
        String policy;
        ScopeRow(String path, String policy) { this.path = path; this.policy = policy; }
    }

    private static final class ScopeModel extends AbstractTableModel {
        final List<ScopeRow> rows = new ArrayList<>();
        void addDefaults() {
            rows.add(new ScopeRow("mods", "managed"));
            rows.add(new ScopeRow("resourcepacks", "managed"));
            rows.add(new ScopeRow("shaderpacks", "managed"));
            rows.add(new ScopeRow("kubejs", "managed"));
            rows.add(new ScopeRow("tacz", "managed"));
            rows.add(new ScopeRow("tlm_custom_pack", "managed"));
            rows.add(new ScopeRow("config", "additive"));
            rows.add(new ScopeRow("defaultconfigs", "additive"));
            rows.add(new ScopeRow("configureddefaults", "first-install"));
            rows.add(new ScopeRow("options.txt", "first-install"));
        }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 2; }
        @Override public String getColumnName(int column) { return column == 0 ? "相对路径" : "策略"; }
        @Override public boolean isCellEditable(int row, int column) { return true; }
        @Override public Object getValueAt(int row, int column) { return column == 0 ? rows.get(row).path : rows.get(row).policy; }
        @Override public void setValueAt(Object value, int row, int column) {
            if (column == 0) rows.get(row).path = String.valueOf(value); else rows.get(row).policy = String.valueOf(value);
            fireTableRowsUpdated(row, row);
        }
    }

    private static final class ConfigRow {
        String path = "config/example.toml";
        String operation = "config-set";
        String format = "toml";
        String key = "section.key";
        String valueType = "boolean";
        String expected = "false";
        String desired = "true";
        String expectedSha256 = "";
        String missingPolicy = "block";
        String conflictPolicy = "block";
        String side = "both";
        String phase = "prelaunch";
        boolean restart = true;
        static ConfigRow defaults() { return new ConfigRow(); }
    }

    private static final class ConfigModel extends AbstractTableModel {
        private final String[] columns = {"路径", "操作", "格式", "key", "值类型", "expected", "desired", "expectedSha256",
                "缺失", "冲突", "作用端", "阶段", "重启"};
        final List<ConfigRow> rows = new ArrayList<>();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) { return column == 12 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int row, int column) { return true; }
        @Override public Object getValueAt(int row, int column) {
            ConfigRow r = rows.get(row);
            return switch (column) {
                case 0 -> r.path; case 1 -> r.operation; case 2 -> r.format; case 3 -> r.key;
                case 4 -> r.valueType; case 5 -> r.expected; case 6 -> r.desired; case 7 -> r.expectedSha256;
                case 8 -> r.missingPolicy; case 9 -> r.conflictPolicy; case 10 -> r.side;
                case 11 -> r.phase; case 12 -> r.restart; default -> "";
            };
        }
        @Override public void setValueAt(Object value, int row, int column) {
            ConfigRow r = rows.get(row);
            switch (column) {
                case 0 -> r.path = String.valueOf(value); case 1 -> r.operation = String.valueOf(value);
                case 2 -> r.format = String.valueOf(value); case 3 -> r.key = String.valueOf(value);
                case 4 -> r.valueType = String.valueOf(value); case 5 -> r.expected = String.valueOf(value);
                case 6 -> r.desired = String.valueOf(value); case 7 -> r.expectedSha256 = String.valueOf(value);
                case 8 -> r.missingPolicy = String.valueOf(value); case 9 -> r.conflictPolicy = String.valueOf(value);
                case 10 -> r.side = String.valueOf(value); case 11 -> r.phase = String.valueOf(value);
                case 12 -> r.restart = Boolean.TRUE.equals(value); default -> { }
            }
            fireTableRowsUpdated(row, row);
        }
    }
}
