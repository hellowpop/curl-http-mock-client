package dev.curlmock;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Font;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** In-application file viewer. File I/O runs outside the Swing event thread. */
final class FileContentPopup {
    private static final int LIMIT = 1024 * 1024;
    private static final String TRUNCATED = "\n[미리보기는 1 MiB까지 표시합니다. 전체 내용은 원본 파일을 확인하세요.]";

    static void open(Component owner, Path file) {
        var dialog = new JDialog(SwingUtilities.getWindowAncestor(owner), file.getFileName().toString(),
                Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        var content = new JTextArea("파일을 읽는 중…");
        content.setEditable(false);
        content.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        content.setMargin(new java.awt.Insets(8, 8, 8, 8));
        var panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(new JLabel(file.toAbsolutePath().toString()), BorderLayout.NORTH);
        panel.add(new JScrollPane(content), BorderLayout.CENTER);
        var close = new JButton("닫기");
        close.addActionListener(event -> dialog.dispose());
        var buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        buttons.add(close);
        panel.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        dialog.setSize(950, 650);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws IOException { return read(file); }
            @Override protected void done() {
                if (!dialog.isDisplayable()) return;
                try { content.setText(get()); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    content.setText("파일 읽기가 중단되었습니다.");
                } catch (ExecutionException e) {
                    content.setText("파일을 읽을 수 없습니다: " + e.getCause().getMessage());
                }
                content.setCaretPosition(0);
            }
        }.execute();
    }

    static String read(Path file) throws IOException {
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")) return workbook(file);
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(LIMIT + 1);
            return new String(bytes, 0, Math.min(bytes.length, LIMIT), StandardCharsets.UTF_8)
                    + (bytes.length > LIMIT ? TRUNCATED : "");
        }
    }

    private static String workbook(Path file) throws IOException {
        try (var input = Files.newInputStream(file); var workbook = new XSSFWorkbook(input)) {
            var text = new StringBuilder();
            var formatter = new DataFormatter();
            for (var sheet : workbook) {
                text.append("--- ").append(sheet.getSheetName()).append(" ---\n");
                for (var row : sheet) {
                    for (int column = 0; column < row.getLastCellNum(); column++) {
                        if (column > 0) text.append('\t');
                        text.append(formatter.formatCellValue(row.getCell(column)));
                        if (text.length() > LIMIT) return text.substring(0, LIMIT) + TRUNCATED;
                    }
                    text.append('\n');
                }
                text.append('\n');
            }
            return text.toString();
        }
    }
}
