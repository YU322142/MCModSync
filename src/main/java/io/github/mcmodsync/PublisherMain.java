package io.github.mcmodsync;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class PublisherMain {
    private static final DisplayLanguage LANGUAGE = DisplayLanguage.detect(null);

    private PublisherMain() {
    }

    public static void main(String[] arguments) {
        if (arguments.length > 0) {
            int status = runCommandLine(arguments);
            if (status != 0) {
                System.exit(status);
            }
            return;
        }

        if (GraphicsEnvironment.isHeadless()) {
            printUsage();
            System.exit(2);
        }
        SwingUtilities.invokeLater(PublisherMain::showWindow);
    }

    private static int runCommandLine(String[] arguments) {
        if (arguments.length == 1 && (arguments[0].equals("--help") || arguments[0].equals("-h"))) {
            printUsage();
            return 0;
        }
        if (arguments.length == 1 && arguments[0].equals("--version")) {
            System.out.println("MCModSync 1.8.1");
            return 0;
        }
        if (arguments.length >= 1 && arguments[0].equals("--upgrade-v2")) {
            if (arguments.length < 2 || arguments.length > 3) {
                printUsage();
                return 2;
            }
            Path modsDirectory = Path.of(arguments[1]).toAbsolutePath().normalize();
            Path output = arguments.length == 3
                    ? Path.of(arguments[2]).toAbsolutePath().normalize()
                    : modsDirectory.resolve(LegacyUpgradeManifest.DEFAULT_FILE_NAME);
            try {
                ModManifest catalog = ModManifest.scan(modsDirectory);
                catalog.ensureUniqueModIds();
                LegacyUpgradeManifest.write(catalog, output);
                System.out.println(text(
                        "1.6.x/1.7 过渡清单生成成功: ",
                        "1.6.x/1.7 transition catalog generated: ") + output);
                return 0;
            } catch (Exception exception) {
                System.err.println(text(
                        "过渡清单生成失败: ",
                        "Transition catalog generation failed: ") + exception.getMessage());
                return 1;
            }
        }
        if (arguments.length >= 1 && arguments[0].equals("--serverlist")) {
            if (arguments.length < 2 || arguments.length > 3) {
                printUsage();
                return 2;
            }
            Path serversDat = Path.of(arguments[1]).toAbsolutePath().normalize();
            Path output = arguments.length == 3
                    ? Path.of(arguments[2]).toAbsolutePath().normalize()
                    : serversDat.getParent().resolve("serverlist.txt");
            try {
                generateServerList(serversDat, output);
                System.out.println(text("服务器列表清单生成成功: ", "Server-list manifest generated: ") + output);
                return 0;
            } catch (Exception exception) {
                System.err.println(text("服务器列表清单生成失败: ", "Failed to generate server-list manifest: ")
                        + exception.getMessage());
                return 1;
            }
        }
        if (arguments.length >= 1 && arguments[0].equals("--resourcepack")) {
            if (arguments.length < 2 || arguments.length > 3) {
                printUsage();
                return 2;
            }
            Path resourcePack = Path.of(arguments[1]).toAbsolutePath().normalize();
            Path output = arguments.length == 3
                    ? Path.of(arguments[2]).toAbsolutePath().normalize()
                    : resourcePack.getParent().resolve("resourcepacks.txt");
            try {
                generateResourcePack(resourcePack, output);
                System.out.println(text("资源包清单生成成功: ", "Resource-pack manifest generated: ") + output);
                return 0;
            } catch (Exception exception) {
                System.err.println(text("资源包清单生成失败: ", "Failed to generate resource-pack manifest: ")
                        + exception.getMessage());
                return 1;
            }
        }
        if (arguments.length < 1 || arguments.length > 2) {
            printUsage();
            return 2;
        }

        Path modsDirectory = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path output = arguments.length == 2
                ? Path.of(arguments[1]).toAbsolutePath().normalize()
                : modsDirectory.resolve("mods.txt");
        try {
            int count = generate(modsDirectory, output);
            System.out.println(text("生成成功，共 ", "Generated ") + count
                    + text(" 个 Mod: ", " mod(s): ") + output);
            return 0;
        } catch (Exception exception) {
            System.err.println(text("生成失败: ", "Generation failed: ") + exception.getMessage());
            return 1;
        }
    }

    private static int generate(Path modsDirectory, Path output) throws IOException {
        ModManifest manifest = ModManifest.scan(modsDirectory);
        try {
            manifest.ensureUniqueModIds();
        } catch (IllegalArgumentException exception) {
            throw new IOException(text(
                    "发布目录包含重复 Fabric Mod ID: ",
                    "The publishing directory contains duplicate Fabric mod IDs: ")
                    + exception.getMessage(), exception);
        }
        manifest.write(output);
        long withoutModId = manifest.entriesWithoutModId();
        if (withoutModId > 0) {
            System.err.println(text("警告：有 ", "Warning: ") + withoutModId
                    + text(
                            " 个 JAR 无法读取 fabric.mod.json/id，版本改名时将回退到文件名识别。",
                            " JAR(s) have no readable fabric.mod.json/id; renamed versions will use filename matching."));
        }
        return manifest.entries().size();
    }

    private static void generateResourcePack(Path resourcePack, Path output) throws IOException {
        ResourcePackManifest.fromFile(resourcePack).write(output);
    }

    private static void generateServerList(Path serversDat, Path output) throws IOException {
        ServerListManifest.fromFile(serversDat).write(output);
    }

    private static void showWindow() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        JFrame frame = new JFrame(text("MCModSync 清单发布工具", "MCModSync Catalog Publisher"));
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(760, 410);
        frame.setLocationRelativeTo(null);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(5, 5, 5, 5);
        constraints.fill = GridBagConstraints.HORIZONTAL;

        JTextField directoryField = new JTextField();
        JButton browseButton = new JButton(text("选择 mods 目录", "Choose mods directory"));
        JButton generateButton = new JButton(text(
                "编辑必须/推荐模组并生成清单", "Edit required/recommended mods and generate catalog"));
        JButton resourcePackButton = new JButton(text(
                "为资源包生成 resourcepacks.txt…", "Generate resourcepacks.txt…"));
        JButton serverListButton = new JButton(text(
                "为服务器列表生成 serverlist.txt…", "Generate serverlist.txt…"));
        JTextArea log = new JTextArea();
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setText(text(
                "选择测试完成的客户端 mods 目录。\n生成的 mods.txt 会放在该目录内，不会修改任何 JAR。\n",
                "Choose a tested client mods directory.\nThe generated mods.txt is saved there; no JAR is modified.\n"));

        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 0;
        form.add(new JLabel(text("Mod 目录：", "Mods directory:")), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        form.add(directoryField, constraints);
        constraints.gridx = 2;
        constraints.weightx = 0;
        form.add(browseButton, constraints);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(serverListButton);
        actions.add(resourcePackButton);
        actions.add(generateButton);
        constraints.gridx = 0;
        constraints.gridy = 1;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        form.add(actions, constraints);

        frame.add(form, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(log);
        scroll.setBorder(BorderFactory.createTitledBorder(text("结果", "Results")));
        frame.add(scroll, BorderLayout.CENTER);

        browseButton.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle(text("选择测试客户端的 mods 目录", "Choose a tested client mods directory"));
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            if (!directoryField.getText().isBlank()) {
                chooser.setCurrentDirectory(Path.of(directoryField.getText()).toFile());
            }
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                directoryField.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
            }
        });

        generateButton.addActionListener(event -> {
            if (directoryField.getText().isBlank()) {
                JOptionPane.showMessageDialog(
                        frame,
                        text("请先选择 mods 目录。", "Choose a mods directory first."),
                        text("缺少目录", "Missing directory"),
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            Path modsDirectory;
            try {
                modsDirectory = Path.of(directoryField.getText()).toAbsolutePath().normalize();
            } catch (Exception exception) {
                JOptionPane.showMessageDialog(
                        frame,
                        text("目录格式无效。", "The directory path is invalid."),
                        text("错误", "Error"),
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
            Path output = modsDirectory.resolve("mods.txt");
            generateButton.setEnabled(false);
            log.append(text(
                    "\n开始读取 Mod 信息并计算 MD5/SHA256：",
                    "\nReading mod metadata and calculating MD5/SHA256: ") + modsDirectory + "\n");
            new SwingWorker<ModManifest, Void>() {
                @Override
                protected ModManifest doInBackground() throws Exception {
                    ModManifest scanned = ModManifest.scan(modsDirectory);
                    scanned.ensureUniqueModIds();
                    return mergeExistingCatalog(scanned, output);
                }

                @Override
                protected void done() {
                    generateButton.setEnabled(true);
                    try {
                        ModManifest scanned = get();
                        var edited = CatalogEditorDialog.edit(frame, scanned);
                        if (edited.isEmpty()) {
                            log.append(text("已取消生成 Mod 清单。\n", "Mod catalog generation cancelled.\n"));
                            return;
                        }
                        edited.get().write(output);
                        Path upgradeOutput = modsDirectory.resolve(LegacyUpgradeManifest.DEFAULT_FILE_NAME);
                        String upgradeNotice;
                        int successMessageType = JOptionPane.INFORMATION_MESSAGE;
                        try {
                            LegacyUpgradeManifest.write(edited.get(), upgradeOutput);
                            log.append(text(
                                    "1.6.x/1.7 过渡清单：",
                                    "1.6.x/1.7 transition catalog: ") + upgradeOutput + "\n");
                            upgradeNotice = text(
                                    "\n\n同时生成 mods-upgrade-v2.txt，可用于先把 1.6.x/1.7 客户端升级到 1.8+。",
                                    "\n\nmods-upgrade-v2.txt was also generated to upgrade 1.6.x/1.7 clients to 1.8+ first.");
                        } catch (IllegalArgumentException exception) {
                            Files.deleteIfExists(upgradeOutput);
                            successMessageType = JOptionPane.WARNING_MESSAGE;
                            upgradeNotice = text(
                                    "\n\n未生成旧版升级清单：" + exception.getMessage()
                                            + "\n如需升级旧客户端，请把当前 MCModSync JAR 放入这个 mods 目录后重新生成。",
                                    "\n\nNo legacy transition catalog was generated: " + exception.getMessage()
                                            + "\nTo upgrade old clients, put the current MCModSync JAR in this mods directory and regenerate.");
                            log.append(upgradeNotice.strip() + "\n");
                        }
                        int count = edited.get().entries().size();
                        log.append(text("完成，共 ", "Completed: ") + count
                                + text(" 个 Mod。\n清单：", " mod(s).\nCatalog: ") + output + "\n");
                        Object[] options = Desktop.isDesktopSupported()
                                ? new Object[]{text("打开所在目录", "Open directory"), text("关闭", "Close")}
                                : new Object[]{text("关闭", "Close")};
                        int choice = JOptionPane.showOptionDialog(
                                frame,
                                text("v4 mods.txt 已生成，共 ", "v4 mods.txt generated with ") + count
                                        + text(
                                                " 个 Mod。\n已包含 MD5、SHA256、必须/推荐分类、平台兼容和中英文描述。",
                                                " mod(s).\nIncludes MD5, SHA256, required/recommended types, platform compatibility, and Chinese/English descriptions.")
                                        + upgradeNotice,
                                text("生成成功", "Generation complete"),
                                JOptionPane.DEFAULT_OPTION,
                                successMessageType,
                                null,
                                options,
                                options[0]);
                        if (choice == 0 && Desktop.isDesktopSupported()) {
                            try {
                                Desktop.getDesktop().open(modsDirectory.toFile());
                            } catch (IOException exception) {
                                log.append(text("无法打开目录：", "Unable to open directory: ")
                                        + exception.getMessage() + "\n");
                            }
                        }
                    } catch (Exception exception) {
                        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                        log.append(text("失败：", "Failed: ") + cause.getMessage() + "\n");
                        JOptionPane.showMessageDialog(
                                frame,
                                cause.getMessage(),
                                text("生成失败", "Generation failed"),
                                JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });

        resourcePackButton.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle(text("选择要发布的资源包 ZIP", "Choose a resource-pack ZIP to publish"));
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new FileNameExtensionFilter(text(
                    "Minecraft 资源包 (*.zip)", "Minecraft resource pack (*.zip)"), "zip"));
            if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            Path resourcePack = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            Path output = resourcePack.getParent().resolve("resourcepacks.txt");
            resourcePackButton.setEnabled(false);
            log.append(text("\n开始计算资源包 MD5：", "\nCalculating resource-pack MD5: ")
                    + resourcePack + "\n");
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    generateResourcePack(resourcePack, output);
                    return null;
                }

                @Override
                protected void done() {
                    resourcePackButton.setEnabled(true);
                    try {
                        get();
                        log.append(text("资源包清单完成：", "Resource-pack manifest completed: ") + output + "\n");
                        Object[] options = Desktop.isDesktopSupported()
                                ? new Object[]{text("打开所在目录", "Open directory"), text("关闭", "Close")}
                                : new Object[]{text("关闭", "Close")};
                        int choice = JOptionPane.showOptionDialog(
                                frame,
                                text(
                                        "resourcepacks.txt 已生成。\n请把它和资源包 ZIP 上传到同一云端目录。",
                                        "resourcepacks.txt was generated.\nUpload it and the resource-pack ZIP to the same cloud directory."),
                                text("资源包清单生成成功", "Resource-pack manifest generated"),
                                JOptionPane.DEFAULT_OPTION,
                                JOptionPane.INFORMATION_MESSAGE,
                                null,
                                options,
                                options[0]);
                        if (choice == 0 && Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().open(resourcePack.getParent().toFile());
                        }
                    } catch (Exception exception) {
                        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                        log.append(text("资源包清单失败：", "Resource-pack manifest failed: ")
                                + cause.getMessage() + "\n");
                        JOptionPane.showMessageDialog(
                                frame,
                                cause.getMessage(),
                                text("资源包清单生成失败", "Resource-pack manifest generation failed"),
                                JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });

        serverListButton.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle(text("选择测试客户端的 servers.dat", "Choose a tested client's servers.dat"));
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setAcceptAllFileFilterUsed(true);
            if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            Path serversDat = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            Path output = serversDat.getParent().resolve("serverlist.txt");
            serverListButton.setEnabled(false);
            log.append(text("\n开始计算服务器列表 MD5：", "\nCalculating server-list MD5: ")
                    + serversDat + "\n");
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    generateServerList(serversDat, output);
                    return null;
                }

                @Override
                protected void done() {
                    serverListButton.setEnabled(true);
                    try {
                        get();
                        log.append(text("服务器列表清单完成：", "Server-list manifest completed: ") + output + "\n");
                        Object[] options = Desktop.isDesktopSupported()
                                ? new Object[]{text("打开所在目录", "Open directory"), text("关闭", "Close")}
                                : new Object[]{text("关闭", "Close")};
                        int choice = JOptionPane.showOptionDialog(
                                frame,
                                text(
                                        "serverlist.txt 已生成。\n请把它和 servers.dat 上传到同一云端目录。",
                                        "serverlist.txt was generated.\nUpload it and servers.dat to the same cloud directory."),
                                text("服务器列表清单生成成功", "Server-list manifest generated"),
                                JOptionPane.DEFAULT_OPTION,
                                JOptionPane.INFORMATION_MESSAGE,
                                null,
                                options,
                                options[0]);
                        if (choice == 0 && Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().open(serversDat.getParent().toFile());
                        }
                    } catch (Exception exception) {
                        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                        log.append(text("服务器列表清单失败：", "Server-list manifest failed: ")
                                + cause.getMessage() + "\n");
                        JOptionPane.showMessageDialog(
                                frame,
                                cause.getMessage(),
                                text("服务器列表清单生成失败", "Server-list manifest generation failed"),
                                JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });

        frame.setVisible(true);
    }

    private static ModManifest mergeExistingCatalog(ModManifest scanned, Path output) {
        if (!Files.isRegularFile(output)) {
            return scanned;
        }
        try {
            ModManifest previous = ModManifest.parse(Files.readString(output));
            if (!previous.supportsRecommendations()) {
                return scanned;
            }
            Map<String, ManifestEntry> byId = new HashMap<>();
            Map<String, ManifestEntry> byName = new HashMap<>();
            for (ManifestEntry entry : previous.entries()) {
                if (!entry.modId().isBlank()) {
                    byId.put(entry.modId(), entry);
                }
                byName.put(entry.fileName().toLowerCase(java.util.Locale.ROOT), entry);
            }
            var merged = scanned.entries().stream().map(current -> {
                ManifestEntry old = !current.modId().isBlank()
                        ? byId.get(current.modId())
                        : byName.get(current.fileName().toLowerCase(java.util.Locale.ROOT));
                if (old == null) {
                    return current;
                }
                return new ManifestEntry(
                        current.sha256(),
                        current.md5(),
                        current.modId(),
                        current.fileName(),
                        old.kind(),
                        old.incompatiblePlatforms(),
                        old.displayName(),
                        current.version().isBlank() ? old.version() : current.version(),
                        old.descriptionZh().isBlank() ? current.descriptionZh() : old.descriptionZh(),
                        old.descriptionEn().isBlank() ? current.descriptionEn() : old.descriptionEn());
            }).toList();
            return ModManifest.fromEntries(previous.catalogVersion(), merged);
        } catch (IOException | IllegalArgumentException exception) {
            return scanned;
        }
    }

    private static void printUsage() {
        System.out.println(text("用法：", "Usage:"));
        System.out.println(text("  双击 JAR：打开图形界面", "  Double-click the JAR to open the GUI"));
        System.out.println(text(
                "  java -jar MCModSync.jar <mods目录> [mods.txt输出路径]",
                "  java -jar MCModSync.jar <mods-directory> [mods.txt-output]"));
        System.out.println(text(
                "  java -jar MCModSync.jar --resourcepack <资源包.zip> [resourcepacks.txt输出路径]",
                "  java -jar MCModSync.jar --resourcepack <resource-pack.zip> [resourcepacks.txt-output]"));
        System.out.println(text(
                "  java -jar MCModSync.jar --serverlist <servers.dat> [serverlist.txt输出路径]",
                "  java -jar MCModSync.jar --serverlist <servers.dat> [serverlist.txt-output]"));
        System.out.println(text(
                "  java -jar MCModSync.jar --upgrade-v2 <mods目录> [mods-upgrade-v2.txt输出路径]",
                "  java -jar MCModSync.jar --upgrade-v2 <mods-directory> [mods-upgrade-v2.txt-output]"));
        System.out.println(text(
                "  语言：-Dmodsync.language=zh_cn 或 -Dmodsync.language=en_us",
                "  Language: -Dmodsync.language=zh_cn or -Dmodsync.language=en_us"));
    }

    private static String text(String chinese, String english) {
        return LANGUAGE.text(chinese, english);
    }
}
