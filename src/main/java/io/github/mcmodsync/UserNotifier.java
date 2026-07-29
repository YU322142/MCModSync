package io.github.mcmodsync;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class UserNotifier implements SyncObserver {
    private static JDialog activeDownloadDialog;
    private static JLabel activePhaseLabel;
    private static JLabel activeFileDetailLabel;
    private static JLabel activeTotalDetailLabel;
    private static JProgressBar activeFileProgressBar;
    private static JProgressBar activeTotalProgressBar;
    private static JTextArea activePlanArea;
    private static JButton activeCloseButton;
    private static volatile Boolean dialogsAvailableCache;
    private final boolean fabricPortableMode;
    private final boolean mobileRuntime;
    private final SyncStatusReporter statusReporter;
    private final AtomicReference<DownloadProgress> pendingProgress = new AtomicReference<>();
    private final AtomicBoolean progressUpdateScheduled = new AtomicBoolean();
    private volatile boolean progressUiStarted;
    private volatile boolean helperExitScheduled;

    UserNotifier() {
        this(false, null);
    }

    UserNotifier(boolean fabricPortableMode) {
        this(fabricPortableMode, null);
    }

    UserNotifier(boolean fabricPortableMode, Path gameDirectory) {
        this.fabricPortableMode = fabricPortableMode;
        Path directory = gameDirectory;
        if (directory == null) {
            String configured = System.getProperty("modsync.gameDir");
            if (configured != null && !configured.isBlank()) {
                directory = Path.of(configured.strip());
            }
        }
        RuntimeEnvironment environment = RuntimeEnvironment.detect();
        this.mobileRuntime = environment.mobile();
        boolean dialogs = dialogsAvailable(environment);
        // Desktop (dialogs available): keep original Swing-only UX.
        // Mobile/headless: log + .modsync status files.
        this.statusReporter = dialogs ? null : new SyncStatusReporter(directory);
        if (this.statusReporter != null) {
            this.statusReporter.setEnvironment(environment);
            this.statusReporter.setMode(
                    mobileRuntime
                            ? (fabricPortableMode ? "portable-mobile" : "agent-mobile")
                            : (fabricPortableMode ? "portable-headless" : "agent-headless"));
            System.out.println("[MCModSync] " + environment.summaryLine());
        }
    }

    private void reportPhase(String phase, String detail) {
        if (statusReporter != null) {
            statusReporter.phase(phase, detail);
        }
    }

    private void reportPlan(String plan) {
        if (statusReporter != null) {
            statusReporter.plan(plan);
        }
    }

    private void reportProgress(DownloadProgress progress) {
        if (statusReporter != null) {
            statusReporter.progress(progress);
        }
    }

    private void reportCompleted(int downloaded, int quarantined, int unchanged) {
        if (statusReporter != null) {
            statusReporter.completed(downloaded, quarantined, unchanged, fabricPortableMode);
        }
    }

    void showWaitingForGameExit(long parentPid) throws IOException {
        progressUiStarted = true;
        String plan = "MCModSync 已检测到 Mod、资源包或服务器列表变化。\n\n"
                + "新版通常会让 Minecraft 自动正常退出。\n"
                + "如果 Fabric、Minecraft 或启动器仍显示错误/退出窗口，请将那个窗口关闭；"
                + "只要游戏 Java 进程结束，下载就会自动继续。\n\n"
                + "请不要再次启动游戏，也不要手动改动 mods 目录。";
        if (!dialogsAvailable()) {
            reportPlan(plan + "\n无独立弹窗环境可查看 .modsync/ui-status.txt、progress.log 与 helper.log。");
            reportPhase("正在等待 Minecraft/Fabric 进程退出……",
                    "进程 PID " + parentPid + "；退出后将自动开始下载");
            return;
        }
        try {
            runOnUiThread(() -> {
                closeActiveDownloadDialog();
                ensureProgressDialog();
                activeDownloadDialog.setTitle("MCModSync 正在准备更新");
                activePhaseLabel.setText("正在等待 Minecraft/Fabric 进程退出……");
                activeFileDetailLabel.setText("进程 PID " + parentPid + "；退出后将自动开始下载");
                setWaitingProgress(activeFileProgressBar, "等待游戏退出");
                activeTotalDetailLabel.setText("总进度：等待开始");
                setWaitingProgress(activeTotalProgressBar, "等待游戏退出");
                activePlanArea.setText(plan);
            });
        } catch (IOException exception) {
            markDialogsUnavailable(exception.getMessage());
        }
    }

    @Override
    public RemovalDecision decideServerRemoved(List<String> serverRemoved) {
        if (serverRemoved.isEmpty()) {
            return RemovalDecision.KEEP;
        }
        if (!dialogsAvailable()) {
            // Desktop headless keeps extras so operators are not surprised.
            // Mobile must clean leftovers (e.g. kuayue / c2me natives) or the next
            // Fabric resolution can hard-fail before preLaunch ever runs again.
            if (mobileRuntime) {
                reportPhase("手机端：自动移出并备份服务器已移除/不在清单中的 Mod",
                        String.join(", ", serverRemoved));
                System.out.println("[MCModSync] 手机端自动隔离服务器已移除 Mod: " + serverRemoved);
                return RemovalDecision.BACKUP;
            }
            reportPhase("无弹窗环境：保留服务器已移除的 Mod 为客户端文件",
                    String.join(", ", serverRemoved));
            System.out.println("[MCModSync] 无弹窗环境，自动保留服务器已移除 Mod: " + serverRemoved);
            return RemovalDecision.KEEP;
        }
        AtomicReference<RemovalDecision> decision = new AtomicReference<>(RemovalDecision.KEEP);
        try {
            runOnUiThread(() -> {
                JTextArea content = new JTextArea(buildRemovedText(serverRemoved));
                content.setEditable(false);
                content.setCaretPosition(0);
                content.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
                content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
                JScrollPane scroll = new JScrollPane(content);
                scroll.setPreferredSize(new Dimension(650, 330));
                Object[] options = {"移出 mods 并备份（推荐）", "保留为客户端 Mod"};
                prepareForInteractiveDecision(
                        "等待确认服务器已移除 Mod",
                        "请在置顶的选择窗口中决定如何处理服务器已移除的 Mod");
                int choice = showTopmostOptionDialog(
                        scroll,
                        "MCModSync：服务器已移除 Mod",
                        options,
                        options[0]);
                decision.set(choice == 0 ? RemovalDecision.BACKUP : RemovalDecision.KEEP);
            });
        } catch (IOException exception) {
            System.err.println("[MCModSync] 无法显示服务器移除选择窗口，将安全保留文件: " + exception.getMessage());
        }
        return decision.get();
    }

    @Override
    public UnknownModDecision decideUnknownClientMod(String fileName) throws IOException {
        if (!dialogsAvailable()) {
            if (mobileRuntime) {
                reportPhase("手机端：自动移出并备份未在云端清单中的本地 Mod", fileName);
                System.out.println("[MCModSync] 手机端自动隔离未确认客户端 Mod: " + fileName);
                return UnknownModDecision.BACKUP;
            }
            // Desktop headless: keep as client mod so operators are not blocked.
            reportPhase("无弹窗环境：保留未在云端清单中的本地 Mod", fileName);
            System.out.println("[MCModSync] 无弹窗环境，自动保留未确认客户端 Mod: " + fileName);
            return UnknownModDecision.KEEP_CLIENT;
        }
        AtomicReference<UnknownModDecision> decision = new AtomicReference<>();
        try {
            runOnUiThread(() -> {
                Object[] options = {"是，作为纯客户端 Mod 保留", "否，移出并备份"};
                prepareForInteractiveDecision(
                        "等待确认纯客户端 Mod",
                        "请在置顶的选择窗口中确认：" + fileName);
                int choice = showTopmostOptionDialog(
                        "本地发现一个云端服务器清单中没有的 Mod：\n\n"
                                + fileName + "\n\n"
                                + "它是否是仅在客户端运行、可以安全保留的纯客户端 Mod？\n"
                                + "如果不确定，请选择“否”；文件只会移入 .modsync/backups，不会永久删除。",
                        "MCModSync：确认纯客户端 Mod",
                        options,
                        options[0]);
                if (choice == 0) {
                    decision.set(UnknownModDecision.KEEP_CLIENT);
                } else if (choice == 1) {
                    decision.set(UnknownModDecision.BACKUP);
                }
            });
        } catch (IOException exception) {
            markDialogsUnavailable(exception.getMessage());
            reportPhase("图形窗口失败，自动保留未确认客户端 Mod", fileName);
            return UnknownModDecision.KEEP_CLIENT;
        }
        if (decision.get() == null) {
            throw new IOException("尚未确认该 Mod 是否为纯客户端 Mod，已阻止启动: " + fileName);
        }
        return decision.get();
    }

    @Override
    public Set<String> chooseRecommendedMods(RecommendedSelectionRequest request) throws IOException {
        if (mobileRuntime) {
            return request.initiallySelected();
        }
        if (!dialogsAvailable()) {
            String detail = "当前桌面环境无法显示选择窗口，将采用所有兼容推荐模组。清单版本: "
                    + request.catalogVersion();
            reportPhase("推荐模组使用默认选择", detail);
            System.out.println("[MCModSync] " + detail);
            return request.initiallySelected();
        }

        AtomicReference<Set<String>> selected = new AtomicReference<>();
        runOnUiThread(() -> selected.set(showRecommendedSelectionDialog(request)));
        return selected.get() == null ? request.initiallySelected() : selected.get();
    }

    private static Set<String> showRecommendedSelectionDialog(RecommendedSelectionRequest request) {
        Map<ManifestEntry, JCheckBox> checkboxes = new LinkedHashMap<>();
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        for (ManifestEntry entry : request.recommendedMods()) {
            boolean compatible = entry.compatibleWith(request.platform());
            JCheckBox checkbox = new JCheckBox();
            checkbox.setSelected(compatible && request.initiallySelected().contains(entry.selectionKey()));
            checkbox.setEnabled(compatible);
            String version = entry.version().isBlank() ? "未知版本" : entry.version();
            String description = entry.description().isBlank() ? "无描述" : escapeHtml(entry.description());
            String compatibility = compatible
                    ? "兼容当前平台"
                    : "不兼容 " + request.platform().displayName() + "，禁止选择";
            checkbox.setText("<html><b>" + escapeHtml(entry.displayName()) + "</b>  "
                    + escapeHtml(version) + "<br>"
                    + "<span style='color:#555'>" + description + "</span><br>"
                    + "<span style='color:" + (compatible ? "#267a35" : "#b42318") + "'>"
                    + compatibility + "</span>  <span style='color:#777'>" + escapeHtml(entry.fileName())
                    + "</span><br><br></html>");
            list.add(checkbox);
            checkboxes.put(entry, checkbox);
        }

        JLabel heading = new JLabel("<html><b>选择本客户端要安装的推荐模组</b><br>"
                + "平台：" + request.platform().displayName()
                + "　推荐清单：" + escapeHtml(request.catalogVersion())
                + (request.previousCatalogVersion().isBlank()
                        ? ""
                        : "（原版本 " + escapeHtml(request.previousCatalogVersion()) + "）")
                + "<br>关闭窗口也会按照当前勾选状态继续同步。</html>");
        heading.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));

        JButton selectCompatible = new JButton("选择全部兼容模组");
        JButton clear = new JButton("一键取消所有推荐模组");
        JButton continueButton = new JButton("按当前选择继续");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(selectCompatible);
        actions.add(clear);
        actions.add(continueButton);

        JPanel content = new JPanel(new BorderLayout());
        content.add(heading, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(760, 480));
        content.add(scroll, BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);

        JDialog owner = activeDownloadDialog != null && activeDownloadDialog.isDisplayable()
                ? activeDownloadDialog
                : null;
        JDialog dialog = new JDialog(owner, "MCModSync 推荐模组选择", true);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.setAlwaysOnTop(true);
        dialog.setAutoRequestFocus(true);
        dialog.add(content);
        dialog.pack();
        dialog.setLocationRelativeTo(null);

        AtomicReference<Set<String>> result = new AtomicReference<>();
        Runnable finish = () -> {
            Set<String> current = new LinkedHashSet<>();
            for (Map.Entry<ManifestEntry, JCheckBox> item : checkboxes.entrySet()) {
                if (item.getValue().isEnabled() && item.getValue().isSelected()) {
                    current.add(item.getKey().selectionKey());
                }
            }
            result.set(Set.copyOf(current));
            dialog.dispose();
        };
        selectCompatible.addActionListener(event -> checkboxes.values().forEach(box -> {
            if (box.isEnabled()) {
                box.setSelected(true);
            }
        }));
        clear.addActionListener(event -> checkboxes.values().forEach(box -> box.setSelected(false)));
        continueButton.addActionListener(event -> finish.run());
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                finish.run();
            }
        });
        dialog.setVisible(true);
        return result.get() == null ? request.initiallySelected() : result.get();
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    @Override
    public void beforeDownload(
            List<String> downloads,
            List<String> replacedOldVersions,
            List<String> rejectedUnknownMods,
            List<String> quarantinedServerRemoved,
            List<String> retainedServerRemoved,
            List<String> retainedClientMods) throws IOException {
        progressUiStarted = true;
        String plan = buildPlanText(
                downloads,
                replacedOldVersions,
                rejectedUnknownMods,
                quarantinedServerRemoved,
                retainedServerRemoved,
                retainedClientMods);
        if (!dialogsAvailable()) {
            reportPlan(plan);
            reportPhase("已检测到 Mod 变化，正在准备下载……",
                    "共需下载或替换 " + downloads.size() + " 个文件");
            return;
        }
        try {
            runOnUiThread(() -> {
                ensureProgressDialog();
                activeDownloadDialog.setTitle("MCModSync 正在自动同步");
                activePhaseLabel.setText("已检测到 Mod 变化，正在准备下载……");
                activeFileDetailLabel.setText("当前文件：准备中");
                setWaitingProgress(activeFileProgressBar, "准备下载");
                if (activeTotalProgressBar.isIndeterminate()) {
                    activeTotalDetailLabel.setText("总进度：共需下载或替换 " + downloads.size() + " 个文件");
                    setWaitingProgress(activeTotalProgressBar, "准备下载");
                } else {
                    activeTotalDetailLabel.setText("总进度：正在准备下一个 BakaXL 同步目标");
                }
                activePlanArea.setText(plan);
                activePlanArea.setCaretPosition(0);
            });
        } catch (IOException exception) {
            markDialogsUnavailable(exception.getMessage());
        }
    }

    @Override
    public void beforeResourcePackDownload(
            List<String> downloads,
            List<String> backedUpRemoved) throws IOException {
        progressUiStarted = true;
        String plan = buildResourcePackPlanText(downloads, backedUpRemoved);
        if (!dialogsAvailable()) {
            reportPlan(plan);
            reportPhase("已检测到资源包变化，正在准备下载……",
                    "共需下载或替换 " + downloads.size() + " 个资源包");
            return;
        }
        try {
            runOnUiThread(() -> {
                ensureProgressDialog();
                activeDownloadDialog.setTitle("MCModSync 正在同步资源包");
                activePhaseLabel.setText("已检测到资源包变化，正在准备下载……");
                activeFileDetailLabel.setText("当前资源包：准备中");
                setWaitingProgress(activeFileProgressBar, "准备下载");
                if (activeTotalProgressBar.isIndeterminate()) {
                    activeTotalDetailLabel.setText("总进度：共需下载或替换 " + downloads.size() + " 个资源包");
                    setWaitingProgress(activeTotalProgressBar, "准备下载");
                } else {
                    activeTotalDetailLabel.setText("总进度：正在准备资源包同步阶段");
                }
                activePlanArea.setText(plan);
                activePlanArea.setCaretPosition(0);
            });
        } catch (IOException exception) {
            markDialogsUnavailable(exception.getMessage());
        }
    }

    @Override
    public void beforeServerListDownload(String fileName) throws IOException {
        progressUiStarted = true;
        String plan = "检测到云端服务器列表 MD5 已变化。\n\n"
                + "将自动下载并校验：" + fileName + "\n\n"
                + "云端维护的服务器条目会按新版本更新或移除。\n"
                + "玩家在多人游戏界面自行添加的服务器地址会原样保留。\n"
                + "现有 servers.dat 会先保存到 .modsync/backups，再安全替换。\n\n"
                + "下载内容只有通过 MD5 复核后才会提交，无需确认。";
        if (!dialogsAvailable()) {
            reportPlan(plan);
            reportPhase("已检测到服务器列表变化，正在自动下载……", "当前文件：" + fileName);
            return;
        }
        try {
            runOnUiThread(() -> {
                ensureProgressDialog();
                activeDownloadDialog.setTitle("MCModSync 正在同步服务器列表");
                activePhaseLabel.setText("已检测到服务器列表变化，正在自动下载……");
                activeFileDetailLabel.setText("当前文件：" + fileName);
                setWaitingProgress(activeFileProgressBar, "准备下载");
                if (activeTotalProgressBar.isIndeterminate()) {
                    activeTotalDetailLabel.setText("总进度：正在准备服务器列表同步阶段");
                    setWaitingProgress(activeTotalProgressBar, "准备下载");
                } else {
                    activeTotalDetailLabel.setText("总进度：正在准备服务器列表同步阶段");
                }
                activePlanArea.setText(plan);
                activePlanArea.setCaretPosition(0);
            });
        } catch (IOException exception) {
            markDialogsUnavailable(exception.getMessage());
        }
    }

    @Override
    public void phaseChanged(String message) {
        if (!progressUiStarted) {
            progressUiStarted = true;
        }
        if (!dialogsAvailable()) {
            reportPhase(message, "正在安全处理，请勿修改 mods 目录");
            return;
        }
        DownloadProgress latestProgress = pendingProgress.getAndSet(null);
        try {
            runOnUiThread(() -> {
                ensureProgressDialog();
                if (latestProgress != null) {
                    applyDownloadProgress(latestProgress);
                }
                activePhaseLabel.setText(message);
                activeFileDetailLabel.setText("正在安全处理，请勿关闭此窗口或修改 mods 目录");
                setWaitingProgress(activeFileProgressBar, "处理中");
            });
        } catch (IOException exception) {
            markDialogsUnavailable(exception.getMessage());
            System.err.println("[MCModSync] 无法更新进度窗口: " + exception.getMessage());
        }
    }

    @Override
    public void downloadProgress(DownloadProgress progress) {
        progressUiStarted = true;
        if (!dialogsAvailable()) {
            reportProgress(progress);
            return;
        }
        pendingProgress.set(progress);
        scheduleProgressFlush();
    }

    @Override
    public void afterUpdate(int downloaded, int quarantined, int unchanged) {
        if (!dialogsAvailable()) {
            reportCompleted(downloaded, quarantined, unchanged);
            if (fabricPortableMode && Boolean.getBoolean("modsync.helperProcess")) {
                // Keep helper process short-lived on mobile/headless; logs already contain the summary.
                helperExitScheduled = false;
            }
            return;
        }
        try {
            runOnUiThread(() -> {
                if (fabricPortableMode) {
                    ensureProgressDialog();
                    activeDownloadDialog.setTitle("MCModSync 更新完成：请再次启动");
                    activeDownloadDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                    activePhaseLabel.setText("Mod、资源包和服务器列表同步已经完成");
                    activeFileDetailLabel.setText("当前文件：全部完成");
                    setCompletedProgress(activeFileProgressBar);
                    activeTotalDetailLabel.setText("总进度：辅助 Java 进程将在 5 秒后自动关闭");
                    setCompletedProgress(activeTotalProgressBar);
                    activePlanArea.setText("下载/替换：" + downloaded + "\n"
                            + "移入备份：" + quarantined + "\n"
                            + "无需更改：" + unchanged + "\n\n"
                            + "Mod 已通过 MD5/SHA256，资源包和服务器列表已通过各自清单校验并安全提交。\n"
                            + "本窗口和下载辅助 Java 进程将在 5 秒后自动关闭。\n"
                            + "之后请回到启动器再点击一次启动；下次校验一致后会直接进入游戏。");
                    activePlanArea.setCaretPosition(0);
                    activeCloseButton.setVisible(true);
                    activeDownloadDialog.pack();
                    helperExitScheduled = true;
                    Timer timer = new Timer(5_000, event -> closeProgressWindowAndExitHelper());
                    timer.setRepeats(false);
                    timer.start();
                    return;
                }
                closeActiveDownloadDialog();
                JDialog dialog = new JDialog((java.awt.Frame) null, "MCModSync 更新完成", false);
                dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                JLabel content = new JLabel("<html><div style='padding:12px'>同步完成。<br><br>"
                        + "下载/替换：" + downloaded + "<br>"
                        + "移入备份：" + quarantined + "<br>"
                        + "无需更改：" + unchanged + "<br><br>"
                        + "游戏将继续自动启动。</div></html>");
                dialog.add(content, BorderLayout.CENTER);
                dialog.pack();
                dialog.setLocationRelativeTo(null);
                dialog.setVisible(true);
                Timer timer = new Timer(5_000, event -> dialog.dispose());
                timer.setRepeats(false);
                timer.start();
            });
        } catch (IOException exception) {
            markDialogsUnavailable(exception.getMessage());
            System.err.println("[MCModSync] 无法显示完成提示: " + exception.getMessage());
        }
    }

    static void showFatalError(Throwable failure) {
        String message = mostUsefulMessage(failure);
        System.err.println("[MCModSync] 游戏启动已阻止: " + message);
        if (!dialogsAvailable()) {
            new SyncStatusReporter(resolveOptionalGameDirectory()).failed(message);
            return;
        }
        try {
            runOnUiThread(() -> {
                closeActiveDownloadDialog();
                JTextArea content = new JTextArea(
                        "为防止加载不完整或损坏的同步内容，游戏启动已被阻止。\n\n"
                                + "错误：" + message + "\n\n"
                                + "请关闭其他 Minecraft/Java 进程，并检查网络、mods.txt、目录写入权限和只读属性后重试。\n"
                                + "完整错误信息仍保留在启动器/Java 启动日志中。");
                content.setEditable(false);
                content.setLineWrap(true);
                content.setWrapStyleWord(true);
                content.setCaretPosition(0);
                content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
                JScrollPane scroll = new JScrollPane(content);
                scroll.setPreferredSize(new Dimension(620, 260));
                JOptionPane.showMessageDialog(
                        null,
                        scroll,
                        "MCModSync 错误：游戏启动已阻止",
                        JOptionPane.ERROR_MESSAGE);
            });
        } catch (IOException exception) {
            markDialogsUnavailable(exception.getMessage());
            System.err.println("[MCModSync] 无法显示错误窗口: " + exception.getMessage());
        }
    }

    static void showInstanceBusy() {
        System.err.println("[MCModSync] 本次启动已取消：请等待同步完成，或关闭该实例的旧 Minecraft/Java 进程后重试。");
        if (!dialogsAvailable()) {
            return;
        }
        try {
            runOnUiThread(() -> JOptionPane.showMessageDialog(
                    null,
                    "该客户端正在由 MCModSync 下载或替换文件，\n"
                            + "或者同一游戏实例已有一个 Minecraft/Java 进程正在运行。\n\n"
                            + "本次启动已安全取消，不是 Mod 崩溃。\n"
                            + "请等待更新窗口显示完成并自动关闭；若没有更新窗口，\n"
                            + "请先关闭旧的 Minecraft/Java，然后再点击启动。",
                    "MCModSync：客户端正在使用中",
                    JOptionPane.WARNING_MESSAGE));
        } catch (IOException exception) {
            System.err.println("[MCModSync] 无法显示客户端占用提示: " + exception.getMessage());
        }
    }

    private static String buildPlanText(
            List<String> downloads,
            List<String> replacedOldVersions,
            List<String> rejectedUnknownMods,
            List<String> quarantinedServerRemoved,
            List<String> retainedServerRemoved,
            List<String> retainedClientMods) {
        StringBuilder result = new StringBuilder();
        result.append("检测到本地 Mod 与云端清单不一致。\n\n");
        result.append("将下载或替换（").append(downloads.size()).append("）：\n");
        if (downloads.isEmpty()) {
            result.append("  （无）\n");
        } else {
            downloads.forEach(name -> result.append("  + ").append(name).append('\n'));
        }
        result.append("\n同一 Mod 的旧版本，将自动移入备份（")
                .append(replacedOldVersions.size()).append("）：\n");
        if (replacedOldVersions.isEmpty()) {
            result.append("  （无）\n");
        } else {
            replacedOldVersions.forEach(name -> result.append("  ↻ ").append(name).append('\n'));
        }
        result.append("\n首次检查中确认不是纯客户端 Mod、将移入备份（")
                .append(rejectedUnknownMods.size()).append("）：\n");
        if (rejectedUnknownMods.isEmpty()) {
            result.append("  （无）\n");
        } else {
            rejectedUnknownMods.forEach(name -> result.append("  ? ").append(name).append('\n'));
        }
        result.append("\n用户选择移出的服务器已移除 Mod（")
                .append(quarantinedServerRemoved.size()).append("）：\n");
        if (quarantinedServerRemoved.isEmpty()) {
            result.append("  （无）\n");
        } else {
            quarantinedServerRemoved.forEach(name -> result.append("  - ").append(name).append('\n'));
        }
        result.append("\n服务器已移除但选择保留（").append(retainedServerRemoved.size()).append("）：\n");
        if (retainedServerRemoved.isEmpty()) {
            result.append("  （无）\n");
        } else {
            retainedServerRemoved.forEach(name -> result.append("  = ").append(name).append('\n'));
        }
        result.append("\n用户自行添加并保留（").append(retainedClientMods.size()).append("）：\n");
        if (retainedClientMods.isEmpty()) {
            result.append("  （无）\n");
        } else {
            retainedClientMods.forEach(name -> result.append("  * ").append(name).append('\n'));
        }
        result.append("\n正在自动下载。Mod 会同时通过 MD5/SHA256 校验后才提交，无需确认。");
        return result.toString();
    }

    private static String buildRemovedText(List<String> serverRemoved) {
        StringBuilder result = new StringBuilder();
        result.append("以下 Mod 存在于上次云端清单，但已从本次云端清单移除：\n\n");
        serverRemoved.forEach(name -> result.append("  - ").append(name).append('\n'));
        result.append("\n选择“移出”时文件不会永久删除，而是保存到 .modsync/backups。\n")
                .append("选择“保留”后，这些文件将视为用户客户端 Mod，后续不再重复询问。\n\n")
                .append("只在本地出现、从未由服务器管理的 Mod 会自动保留。");
        return result.toString();
    }

    private static String buildResourcePackPlanText(
            List<String> downloads,
            List<String> backedUpRemoved) {
        StringBuilder result = new StringBuilder();
        result.append("检测到本地资源包与云端 MD5 清单不一致。\n\n");
        result.append("将下载或替换（").append(downloads.size()).append("）：\n");
        if (downloads.isEmpty()) {
            result.append("  （无）\n");
        } else {
            downloads.forEach(name -> result.append("  + ").append(name).append('\n'));
        }
        result.append("\n云端清单已移除并将备份（").append(backedUpRemoved.size()).append("）：\n");
        if (backedUpRemoved.isEmpty()) {
            result.append("  （无）\n");
        } else {
            backedUpRemoved.forEach(name -> result.append("  - ").append(name).append('\n'));
        }
        result.append("\n玩家自行添加、未出现在云端清单中的其他资源包会原样保留。\n")
                .append("下载内容只有通过 MD5 复核后才会替换本地文件。");
        return result.toString();
    }

    private static void ensureProgressDialog() {
        if (activeDownloadDialog != null && activeDownloadDialog.isDisplayable()) {
            return;
        }

        activePhaseLabel = new JLabel("MCModSync 正在准备更新……");
        activePhaseLabel.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));

        activePlanArea = new JTextArea();
        activePlanArea.setEditable(false);
        activePlanArea.setLineWrap(true);
        activePlanArea.setWrapStyleWord(true);
        activePlanArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        activePlanArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JScrollPane scroll = new JScrollPane(activePlanArea);
        scroll.setPreferredSize(new Dimension(700, 300));

        activeFileDetailLabel = new JLabel("当前文件：准备中");
        activeFileDetailLabel.setBorder(BorderFactory.createEmptyBorder(4, 2, 6, 2));
        activeFileProgressBar = new JProgressBar(0, 1000);
        setWaitingProgress(activeFileProgressBar, "准备中");

        activeTotalDetailLabel = new JLabel("总进度：准备中");
        activeTotalDetailLabel.setBorder(BorderFactory.createEmptyBorder(10, 2, 6, 2));
        activeTotalProgressBar = new JProgressBar(0, 1000);
        setWaitingProgress(activeTotalProgressBar, "准备中");

        activeCloseButton = new JButton("关闭");
        activeCloseButton.setVisible(false);
        activeCloseButton.addActionListener(event -> closeProgressWindowAndExitHelper());
        JPanel buttonRow = new JPanel(new BorderLayout());
        buttonRow.add(activeCloseButton, BorderLayout.EAST);

        JPanel progressPanel = new JPanel();
        progressPanel.setLayout(new BoxLayout(progressPanel, BoxLayout.Y_AXIS));
        progressPanel.setBorder(BorderFactory.createEmptyBorder(6, 12, 12, 12));
        progressPanel.add(activeFileDetailLabel);
        progressPanel.add(activeFileProgressBar);
        progressPanel.add(activeTotalDetailLabel);
        progressPanel.add(activeTotalProgressBar);
        progressPanel.add(buttonRow);

        JDialog dialog = new JDialog((java.awt.Frame) null, "MCModSync 正在自动同步", false);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.setAlwaysOnTop(true);
        dialog.setAutoRequestFocus(true);
        dialog.add(activePhaseLabel, BorderLayout.NORTH);
        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(progressPanel, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        dialog.toFront();
        dialog.requestFocus();
        activeDownloadDialog = dialog;
    }

    private static void prepareForInteractiveDecision(String phase, String detail) {
        if (activeDownloadDialog == null || !activeDownloadDialog.isDisplayable()) {
            return;
        }
        activeDownloadDialog.setTitle("MCModSync 等待你的选择");
        activePhaseLabel.setText(phase);
        activeFileDetailLabel.setText(detail);
        setWaitingProgress(activeFileProgressBar, "等待选择");
        activeDownloadDialog.toFront();
    }

    private static int showTopmostOptionDialog(
            Object message,
            String title,
            Object[] options,
            Object initialValue) {
        JDialog owner = activeDownloadDialog != null && activeDownloadDialog.isDisplayable()
                ? activeDownloadDialog
                : null;
        boolean restoreOwnerAlwaysOnTop = owner != null && owner.isAlwaysOnTop();
        if (owner != null) {
            // A topmost progress window can otherwise cover a JOptionPane that
            // was created without an owner. Temporarily lower the owner and
            // make the actual decision dialog topmost instead.
            owner.setAlwaysOnTop(false);
        }

        JOptionPane pane = new JOptionPane(
                message,
                JOptionPane.QUESTION_MESSAGE,
                JOptionPane.DEFAULT_OPTION,
                null,
                options,
                initialValue);
        JDialog decisionDialog = pane.createDialog(owner, title);
        decisionDialog.setAlwaysOnTop(true);
        decisionDialog.setAutoRequestFocus(true);
        decisionDialog.setModal(true);
        decisionDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        SwingUtilities.invokeLater(() -> {
            decisionDialog.toFront();
            decisionDialog.requestFocus();
        });

        try {
            decisionDialog.setVisible(true);
            Object selected = pane.getValue();
            for (int index = 0; index < options.length; index++) {
                if (Objects.equals(selected, options[index])) {
                    return index;
                }
            }
            return JOptionPane.CLOSED_OPTION;
        } finally {
            decisionDialog.dispose();
            if (owner != null && owner.isDisplayable()) {
                owner.setAlwaysOnTop(restoreOwnerAlwaysOnTop);
                owner.toFront();
                owner.requestFocus();
            }
        }
    }

    private void scheduleProgressFlush() {
        if (progressUpdateScheduled.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(this::flushDownloadProgress);
        }
    }

    private void flushDownloadProgress() {
        try {
            DownloadProgress snapshot = pendingProgress.getAndSet(null);
            if (snapshot != null) {
                applyDownloadProgress(snapshot);
            }
        } finally {
            progressUpdateScheduled.set(false);
            if (pendingProgress.get() != null) {
                scheduleProgressFlush();
            }
        }
    }

    private static void applyDownloadProgress(DownloadProgress snapshot) {
        ensureProgressDialog();
        activeDownloadDialog.toFront();
        activeDownloadDialog.setTitle("MCModSync 正在下载");
        activePhaseLabel.setText("正在下载 [" + snapshot.fileIndex() + "/" + snapshot.fileCount()
                + "] " + snapshot.fileName());

        String downloaded = formatBytes(snapshot.fileDownloadedBytes());
        if (snapshot.fileTotalBytes() > 0) {
            double fraction = Math.min(1.0, (double) snapshot.fileDownloadedBytes() / snapshot.fileTotalBytes());
            int value = (int) Math.round(fraction * 1000.0);
            setProgress(activeFileProgressBar, value,
                    String.format(Locale.ROOT, "%.1f%%", fraction * 100.0));
            activeFileDetailLabel.setText("当前文件：" + downloaded + " / "
                    + formatBytes(snapshot.fileTotalBytes()));
        } else {
            setWaitingProgress(activeFileProgressBar, "已下载 " + downloaded);
            activeFileDetailLabel.setText("当前文件：服务器未提供大小；已下载 " + downloaded);
        }

        int totalValue = Math.max(0, Math.min(1000, snapshot.totalPermille()));
        setProgress(activeTotalProgressBar, totalValue,
                String.format(Locale.ROOT, "%.1f%%", totalValue / 10.0));
        if (snapshot.totalBytes() > 0 && snapshot.totalDownloadedBytes() >= 0) {
            activeTotalDetailLabel.setText("总进度：" + formatBytes(snapshot.totalDownloadedBytes())
                    + " / " + formatBytes(snapshot.totalBytes()));
        } else {
            activeTotalDetailLabel.setText("总进度：正在处理第 " + snapshot.fileIndex()
                    + " / " + snapshot.fileCount() + " 个文件（按文件/同步目标估算）");
        }
    }

    private static void setWaitingProgress(JProgressBar progressBar, String text) {
        progressBar.setIndeterminate(true);
        progressBar.setStringPainted(true);
        progressBar.setString(text);
    }

    private static void setProgress(JProgressBar progressBar, int value, String text) {
        progressBar.setIndeterminate(false);
        progressBar.setMinimum(0);
        progressBar.setMaximum(1000);
        progressBar.setValue(value);
        progressBar.setStringPainted(true);
        progressBar.setString(text);
    }

    private static void setCompletedProgress(JProgressBar progressBar) {
        setProgress(progressBar, 1000, "100% — 更新完成");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static void closeActiveDownloadDialog() {
        if (activeDownloadDialog != null) {
            activeDownloadDialog.dispose();
            activeDownloadDialog = null;
        }
        activePhaseLabel = null;
        activeFileDetailLabel = null;
        activeTotalDetailLabel = null;
        activeFileProgressBar = null;
        activeTotalProgressBar = null;
        activePlanArea = null;
        activeCloseButton = null;
    }

    boolean helperExitScheduled() {
        return helperExitScheduled;
    }

    private static void closeProgressWindowAndExitHelper() {
        closeActiveDownloadDialog();
        if (Boolean.getBoolean("modsync.helperProcess")) {
            System.exit(0);
        }
    }

    private static String mostUsefulMessage(Throwable failure) {
        Throwable current = failure;
        String selected = failure.getClass().getSimpleName();
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                selected = current.getMessage();
            }
            current = current.getCause();
        }
        return selected;
    }

    static boolean dialogsAvailable() {
        return dialogsAvailable(null);
    }

    static boolean dialogsAvailable(RuntimeEnvironment environment) {
        if (Boolean.getBoolean("modsync.disableDialogs") || Boolean.getBoolean("modsync.forceHeadless")) {
            dialogsAvailableCache = false;
            return false;
        }
        if (dialogsAvailableCache != null) {
            return dialogsAvailableCache;
        }
        RuntimeEnvironment detected = environment == null ? RuntimeEnvironment.detect() : environment;
        if (!detected.dialogsUsable()) {
            dialogsAvailableCache = false;
            return false;
        }
        try {
            boolean available = !GraphicsEnvironment.isHeadless();
            dialogsAvailableCache = available;
            return available;
        } catch (Throwable failure) {
            dialogsAvailableCache = false;
            return false;
        }
    }

    static void resetDialogsAvailabilityForTests() {
        dialogsAvailableCache = null;
    }

    static void markDialogsUnavailable(String reason) {
        dialogsAvailableCache = false;
        System.setProperty("modsync.disableDialogs", "true");
        System.err.println("[MCModSync] 图形窗口不可用，已切换为无弹窗模式"
                + (reason == null || reason.isBlank() ? "" : ": " + reason));
    }

    private static Path resolveOptionalGameDirectory() {
        String configured = System.getProperty("modsync.gameDir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.strip());
        }
        return null;
    }

    private static void runOnUiThread(Runnable action) throws IOException {
        if (!dialogsAvailable()) {
            throw new IOException("当前环境不支持图形提示窗口");
        }
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("等待提示窗口时线程被中断", exception);
        } catch (InvocationTargetException exception) {
            markDialogsUnavailable(String.valueOf(exception.getCause()));
            throw new IOException("显示提示窗口失败", exception.getCause());
        } catch (RuntimeException exception) {
            markDialogsUnavailable(exception.getMessage());
            throw new IOException("显示提示窗口失败", exception);
        }
    }
}
