package dev.curlmock;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.function.Consumer;
import javax.swing.*;

/** Scrollable result text with file-name buttons anchored below the text. */
final class ResultPane extends JPanel {
    private final JTextArea text = new JTextArea();
    final JPanel files = new JPanel(new GridLayout(0, 1, 0, 4));
    private final Consumer<Path> openFile;

    ResultPane(Consumer<Path> openFile) {
        super(new BorderLayout(0, 6));
        this.openFile = openFile;
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        text.setMargin(new java.awt.Insets(8, 8, 8, 8));
        add(new JScrollPane(text), BorderLayout.CENTER);
        files.setBorder(BorderFactory.createTitledBorder("결과 파일"));
        files.setVisible(false);
        add(files, BorderLayout.SOUTH);
    }

    void setText(String value) {
        text.setText(value);
        files.removeAll();
        files.setVisible(false);
        revalidate();
        repaint();
    }

    String getText() { return text.getText(); }

    void setCaretPosition(int position) { text.setCaretPosition(position); }

    void appendFile(String label, Path file) {
        var button = new JButton("[" + label + "] " + file.getFileName());
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setToolTipText(label + ": " + file.toAbsolutePath());
        button.getAccessibleContext().setAccessibleName(label + ": " + file.getFileName());
        button.addActionListener(event -> openFile.accept(file));
        files.add(button);
        files.setVisible(true);
        revalidate();
        repaint();
    }

    void showRun(BatchExecutor.RunResult run, String responsePreview) {
        if (run.transactions().isEmpty()) {
            setText("실행이 중단되었습니다.\n");
            appendFile("결과 Excel", run.workbook());
            return;
        }
        var transaction = run.transactions().getFirst();
        setText("Result: " + (run.success() ? "SUCCESS" : "FAILED")
                + "\nHTTP status: " + transaction.httpStatus()
                + "\nCurl exit code: " + transaction.curlExitCode()
                + "\nElapsed: " + transaction.elapsedMs() + " ms"
                + "\nUUID: " + transaction.uuid()
                + "\nError: " + transaction.error()
                + "\nArtifacts: " + run.artifactsDirectory()
                + "\n\n--- Request headers ---\n" + transaction.requestHeaders()
                + "\n\n--- Response headers ---\n" + transaction.responseHeaders()
                + "\n\n--- Response body (UTF-8 preview) ---\n" + responsePreview);
        appendFile("결과 Excel", run.workbook());
        appendFile("curl 로그", transaction.curlLog());
        appendFile("요청 본문", transaction.requestBody());
        appendFile("응답 본문", transaction.responseBody());
    }
}
