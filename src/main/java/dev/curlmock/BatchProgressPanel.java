package dev.curlmock;

import java.awt.BorderLayout;
import java.awt.Font;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.*;

/** Live execution log and result-file access for the modal batch dialog. */
final class BatchProgressPanel extends JPanel {
    private final ClientConfig config;
    private final Consumer<Path> openFile;
    private final String mode;
    private boolean started;
    final JTextArea log = new JTextArea();
    final JButton viewResults = new JButton("결과파일 보기");
    final JButton close = new JButton("닫기");
    private Path workbook;

    BatchProgressPanel(ClientConfig config) { this(config, "전체실행", null); }

    BatchProgressPanel(ClientConfig config, Consumer<Path> openFile) {
        this(config, "전체실행", openFile);
    }

    BatchProgressPanel(ClientConfig config, String mode, Consumer<Path> openFile) {
        super(new BorderLayout(0, 8));
        this.config = config;
        this.mode = mode;
        this.openFile = openFile == null ? path -> FileContentPopup.open(this, path) : openFile;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(new JLabel(mode + " — " + config.payloadTypes().size() + "개 케이스"), BorderLayout.NORTH);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        log.setMargin(new java.awt.Insets(8, 8, 8, 8));
        add(new JScrollPane(log), BorderLayout.CENTER);
        var buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        viewResults.setVisible(false);
        viewResults.addActionListener(event -> { if (workbook != null) this.openFile.accept(workbook); });
        close.setEnabled(false);
        buttons.add(viewResults);
        buttons.add(close);
        add(buttons, BorderLayout.SOUTH);
    }

    void start() {
        if (started) return;
        started = true;
        append(mode + " 시작");
        new SwingWorker<BatchExecutor.RunResult, String>() {
            @Override protected BatchExecutor.RunResult doInBackground() throws java.io.IOException {
                return new BatchExecutor().run(config, this::publish);
            }
            @Override protected void process(List<String> messages) { messages.forEach(BatchProgressPanel.this::append); }
            @Override protected void done() {
                try {
                    var run = get();
                    long successes = run.transactions().stream().filter(TransactionResult::success).count();
                    append((run.interrupted() ? "실행 중단" : mode + " 완료") + "; 총: " + run.transactions().size()
                            + "; 성공: " + successes + "; 실패: " + (run.transactions().size() - successes));
                    workbook = run.workbook();
                    viewResults.setToolTipText(workbook.toString());
                    viewResults.setVisible(!run.interrupted() && run.transactions().size() == config.payloadTypes().size());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    append("실행 결과 수신이 중단되었습니다.");
                } catch (ExecutionException e) {
                    append("Execution error: " + e.getCause().getMessage());
                } finally {
                    close.setEnabled(true);
                    revalidate();
                    repaint();
                }
            }
        }.execute();
    }

    private void append(String message) {
        log.append(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                + " " + message + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }
}
