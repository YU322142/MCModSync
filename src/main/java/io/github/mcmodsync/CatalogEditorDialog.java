package io.github.mcmodsync;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

final class CatalogEditorDialog {
    private static final int TYPE_COLUMN = 1;

    private CatalogEditorDialog() {
    }

    static Optional<ModManifest> edit(JFrame owner, ModManifest scanned) {
        String[] columns = {
                "文件名", "类型", "显示名称", "版本",
                "Windows 不兼容", "Mac 不兼容", "Linux 不兼容", "手机不兼容", "描述"
        };
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column != 0;
            }

            @Override
            public Class<?> getColumnClass(int column) {
                return column >= 4 && column <= 7 ? Boolean.class : String.class;
            }
        };
        for (ManifestEntry entry : scanned.entries()) {
            model.addRow(new Object[]{
                    entry.fileName(),
                    entry.kind().id(),
                    entry.displayName(),
                    entry.version(),
                    entry.incompatiblePlatforms().contains(ClientPlatform.WINDOWS),
                    entry.incompatiblePlatforms().contains(ClientPlatform.MAC),
                    entry.incompatiblePlatforms().contains(ClientPlatform.LINUX),
                    entry.incompatiblePlatforms().contains(ClientPlatform.MOBILE),
                    entry.description()
            });
        }

        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(26);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        TableColumn typeColumn = table.getColumnModel().getColumn(TYPE_COLUMN);
        typeColumn.setCellEditor(new javax.swing.DefaultCellEditor(
                new JComboBox<>(new String[]{"required", "recommended"})));
        int[] widths = {210, 105, 180, 90, 125, 105, 115, 105, 360};
        for (int index = 0; index < widths.length; index++) {
            table.getColumnModel().getColumn(index).setPreferredWidth(widths[index]);
        }

        JTextField versionField = new JTextField(scanned.catalogVersion(), 24);
        JPanel heading = new JPanel(new FlowLayout(FlowLayout.LEFT));
        heading.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        heading.add(new JLabel("推荐清单版本："));
        heading.add(versionField);
        heading.add(new JLabel("类型为 recommended 时，可标记不兼容平台；客户端其余平台默认勾选。"));

        JButton allRequired = new JButton("所选设为必须模组");
        JButton allRecommended = new JButton("所选设为推荐模组");
        JButton generate = new JButton("生成 v3 清单");
        JButton cancel = new JButton("取消");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(allRequired);
        actions.add(allRecommended);
        actions.add(cancel);
        actions.add(generate);

        JDialog dialog = new JDialog(owner, "MCModSync 必须/推荐模组清单编辑器", true);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.add(heading, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(1_120, 560));
        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(actions, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);

        AtomicReference<ModManifest> result = new AtomicReference<>();
        allRequired.addActionListener(event -> setSelectedType(table, model, "required"));
        allRecommended.addActionListener(event -> setSelectedType(table, model, "recommended"));
        cancel.addActionListener(event -> dialog.dispose());
        generate.addActionListener(event -> {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
            try {
                List<ManifestEntry> edited = new ArrayList<>();
                for (int row = 0; row < model.getRowCount(); row++) {
                    ManifestEntry source = scanned.entries().get(row);
                    ModKind kind = ModKind.parse(string(model, row, TYPE_COLUMN));
                    EnumSet<ClientPlatform> incompatible = EnumSet.noneOf(ClientPlatform.class);
                    if (bool(model, row, 4)) incompatible.add(ClientPlatform.WINDOWS);
                    if (bool(model, row, 5)) incompatible.add(ClientPlatform.MAC);
                    if (bool(model, row, 6)) incompatible.add(ClientPlatform.LINUX);
                    if (bool(model, row, 7)) incompatible.add(ClientPlatform.MOBILE);
                    edited.add(new ManifestEntry(
                            source.sha256(),
                            source.md5(),
                            source.modId(),
                            source.fileName(),
                            kind,
                            Set.copyOf(incompatible),
                            string(model, row, 2),
                            string(model, row, 3),
                            string(model, row, 8)));
                }
                ModManifest manifest = ModManifest.fromEntries(versionField.getText(), edited);
                manifest.ensureUniqueModIds();
                result.set(manifest);
                dialog.dispose();
            } catch (RuntimeException exception) {
                JOptionPane.showMessageDialog(
                        dialog,
                        exception.getMessage(),
                        "清单内容无效",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                dialog.dispose();
            }
        });
        dialog.setVisible(true);
        return Optional.ofNullable(result.get());
    }

    private static void setSelectedType(JTable table, DefaultTableModel model, String type) {
        int[] selected = table.getSelectedRows();
        if (selected.length == 0) {
            for (int row = 0; row < model.getRowCount(); row++) {
                model.setValueAt(type, row, TYPE_COLUMN);
            }
            return;
        }
        for (int viewRow : selected) {
            model.setValueAt(type, table.convertRowIndexToModel(viewRow), TYPE_COLUMN);
        }
    }

    private static String string(DefaultTableModel model, int row, int column) {
        Object value = model.getValueAt(row, column);
        return value == null ? "" : value.toString();
    }

    private static boolean bool(DefaultTableModel model, int row, int column) {
        return Boolean.TRUE.equals(model.getValueAt(row, column));
    }
}
