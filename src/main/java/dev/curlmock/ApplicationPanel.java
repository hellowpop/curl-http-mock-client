package dev.curlmock;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Swing view over the existing configuration and batch execution pipeline. */
final class ApplicationPanel extends JPanel {
    private final ClientConfig config;
    final JList<PayloadType> requests;
    final JTextArea summary = textArea();
    final ResultPane result = new ResultPane(path -> FileContentPopup.open(this, path));
    final JButton execute = new JButton("실행");
    final JButton executeAll = new JButton("전체실행");
    final JButton executeFiltered = new JButton("선택실행");
    final JTextField search = new JTextField();
    final JButton clearSearch = new JButton("×");
    private final JLabel filterStatus = new JLabel();
    private final List<Integer> visibleIndices = new ArrayList<>();
    private boolean filtering;

    ApplicationPanel(ClientConfig config) {
        super(new BorderLayout());
        config.validate();
        this.config = config;
        for (int i = 0; i < config.payloadTypes().size(); i++) visibleIndices.add(i);
        setBorder(new EmptyBorder(10, 10, 10, 10));
        requests = new JList<>(config.payloadTypes().toArray(PayloadType[]::new));
        requests.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        requests.setCellRenderer(new RequestListRenderer(() -> search.getText().strip(),
                index -> visibleIndices.get(index) + 1));
        var left = section("요청 목록 (" + config.payloadTypes().size() + ")", new JScrollPane(requests));
        var searchRow = new JPanel(new BorderLayout(6, 0));
        var searchLabel = new JLabel("검색");
        searchLabel.setLabelFor(search);
        search.setToolTipText("공백·콤마로 구분한 모든 키워드가 포함된 요청 검색 (대소문자 구분 없음)");
        clearSearch.setToolTipText("검색 취소");
        clearSearch.getAccessibleContext().setAccessibleName("검색 취소");
        clearSearch.setEnabled(false);
        searchRow.add(searchLabel, BorderLayout.WEST);
        searchRow.add(search, BorderLayout.CENTER);
        searchRow.add(clearSearch, BorderLayout.EAST);
        var searchArea = new JPanel(new BorderLayout(0, 4));
        searchArea.add(searchRow, BorderLayout.NORTH);
        searchArea.add(filterStatus, BorderLayout.SOUTH);
        filterStatus.setText(config.payloadTypes().size() + " / " + config.payloadTypes().size() + " 케이스");
        left.add(searchArea, BorderLayout.NORTH);
        var batchButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        executeFiltered.setEnabled(false);
        executeFiltered.setToolTipText("현재 검색 결과에 표시된 케이스 모두 실행");
        batchButtons.add(executeFiltered);
        batchButtons.add(executeAll);
        left.add(batchButtons, BorderLayout.SOUTH);
        left.setMinimumSize(new Dimension(220, 100));
        var top = section("실행 요약", new JScrollPane(summary));
        var buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        buttons.add(execute);
        top.add(buttons, BorderLayout.SOUTH);
        var right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, section("실행 결과", result));
        right.setResizeWeight(0.4);
        right.setDividerLocation(240);
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.3);
        split.setDividerLocation(340);
        add(split);
        requests.addListSelectionListener(event -> {
            if (!filtering && !event.getValueIsAdjusting()) showSelection();
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filterRequests(); }
            @Override public void removeUpdate(DocumentEvent event) { filterRequests(); }
            @Override public void changedUpdate(DocumentEvent event) { filterRequests(); }
        });
        clearSearch.addActionListener(event -> {
            search.setText("");
            search.requestFocusInWindow();
        });
        execute.addActionListener(event -> runSelected());
        executeAll.addActionListener(event -> runBatch(false));
        executeFiltered.addActionListener(event -> runBatch(true));
        requests.setSelectedIndex(0);
    }

    private void filterRequests() {
        int selected = requests.getSelectedIndex() < 0 ? -1 : visibleIndices.get(requests.getSelectedIndex());
        var tokens = RequestSearch.tokens(search.getText());
        filtering = true;
        try {
            var model = new DefaultListModel<PayloadType>();
            visibleIndices.clear();
            for (int i = 0; i < config.payloadTypes().size(); i++) {
                var type = config.payloadTypes().get(i);
                String path = type.path().toLowerCase(Locale.ROOT);
                if (tokens.stream().allMatch(path::contains)) {
                    visibleIndices.add(i);
                    model.addElement(type);
                }
            }
            requests.setModel(model);
            int preserved = visibleIndices.indexOf(selected);
            if (!model.isEmpty()) requests.setSelectedIndex(preserved >= 0 ? preserved : 0);
            clearSearch.setEnabled(search.isEnabled() && !search.getText().isEmpty());
            filterStatus.setText(visibleIndices.isEmpty() ? "검색 결과가 없습니다 (0 케이스)" :
                    visibleIndices.size() + " / " + config.payloadTypes().size() + " 케이스");
            int current = requests.getSelectedIndex() < 0 ? -1 : visibleIndices.get(requests.getSelectedIndex());
            if (current != selected || current < 0) showSelection();
            updateFilteredButton();
        } finally { filtering = false; }
    }

    static void open(ClientConfig config) throws IOException {
        if (GraphicsEnvironment.isHeadless()) throw new IOException("--application requires a graphical desktop (headless environment)");
        try {
            SwingUtilities.invokeAndWait(() -> {
                var frame = new JFrame("curl HTTP Mock Client");
                frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
                frame.setContentPane(new ApplicationPanel(config));
                frame.setMinimumSize(new Dimension(800, 500));
                frame.setSize(1150, 760);
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Application launch interrupted", e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IOException("Cannot open application: " + e.getCause().getMessage(), e.getCause());
        }
    }

    private void showSelection() {
        var type = requests.getSelectedValue();
        execute.setEnabled(type != null);
        summary.setText(type == null ? "검색 결과가 없습니다. 검색어를 변경하거나 취소하세요." :
                "Method: " + config.method()
                + "\nURL: " + config.endpointUrl().replaceAll("/+$", "") + type.path()
                + "\nContent-Type: " + type.contentType()
                + "\nTransfer-Encoding: " + type.transferEncoding()
                + "\nPayload: " + type.payloadSize() + " (" + type.payloadSize().bytes() + " bytes)"
                + "\nConnect timeout: " + type.effectiveConnectTimeoutSeconds(config) + " s"
                + "\nRequest timeout: " + type.effectiveRequestTimeoutSeconds(config) + " s"
                + "\nCurl: " + config.curlExecutable()
                + "\nCurl arguments: " + config.curlArguments()
                + "\nOutput: " + Path.of(config.outputDirectory()).toAbsolutePath().normalize());
        summary.setCaretPosition(0);
        result.setText(type == null ? "" : "실행 버튼을 누르면 선택한 요청의 결과가 표시됩니다.");
    }

    private void runSelected() {
        var type = requests.getSelectedValue();
        if (type == null || !execute.isEnabled()) return;
        setRunning(true);
        result.setText("실행 중…\n" + type.path());
        var selected = new ClientConfig(config.endpointUrl(), config.method(), config.curlExecutable(),
                config.connectTimeoutSeconds(), config.requestTimeoutSeconds(), config.outputDirectory(),
                List.of(type), config.curlArguments());
        new SwingWorker<RunDisplay, Void>() {
            @Override protected RunDisplay doInBackground() throws IOException {
                var run = new BatchExecutor().run(selected);
                return new RunDisplay(run, run.transactions().isEmpty() ? "" : preview(run.transactions().getFirst().responseBody()));
            }
            @Override protected void done() {
                try {
                    var display = get();
                    result.showRun(display.run(), display.preview());
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result.setText("실행 결과 수신이 중단되었습니다.");
                } catch (ExecutionException e) {
                    result.setText("Execution error: " + e.getCause().getMessage());
                } finally {
                    result.setCaretPosition(0);
                    setRunning(false);
                }
            }
        }.execute();
    }

    private void setRunning(boolean running) {
        requests.setEnabled(!running);
        search.setEnabled(!running);
        clearSearch.setEnabled(!running && !search.getText().isEmpty());
        execute.setEnabled(!running && requests.getSelectedValue() != null);
        executeAll.setEnabled(!running);
        updateFilteredButton();
    }

    private void updateFilteredButton() {
        executeFiltered.setEnabled(search.isEnabled() && !RequestSearch.tokens(search.getText()).isEmpty()
                && !visibleIndices.isEmpty());
    }

    BatchProgressPanel createBatch(boolean filtered) {
        if (!filtered) return new BatchProgressPanel(config);
        if (RequestSearch.tokens(search.getText()).isEmpty() || visibleIndices.isEmpty())
            throw new IllegalStateException("선택실행에는 검색 결과가 필요합니다.");
        var types = visibleIndices.stream().map(config.payloadTypes()::get).toList();
        var selected = new ClientConfig(config.endpointUrl(), config.method(), config.curlExecutable(),
                config.connectTimeoutSeconds(), config.requestTimeoutSeconds(), config.outputDirectory(),
                types, config.curlArguments());
        return new BatchProgressPanel(selected, "선택실행", null);
    }

    private void runBatch(boolean filtered) {
        if (!(filtered ? executeFiltered : executeAll).isEnabled()) return;
        var panel = createBatch(filtered);
        setRunning(true);
        try {
            var dialog = new JDialog(SwingUtilities.getWindowAncestor(this), filtered ? "선택실행" : "전체실행",
                    java.awt.Dialog.ModalityType.APPLICATION_MODAL);
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(java.awt.event.WindowEvent event) {
                    if (panel.close.isEnabled()) dialog.dispose();
                }
            });
            panel.close.addActionListener(event -> dialog.dispose());
            dialog.setContentPane(panel);
            dialog.setSize(950, 650);
            dialog.setLocationRelativeTo(this);
            panel.start();
            dialog.setVisible(true);
        } finally { setRunning(false); }
    }

    private record RunDisplay(BatchExecutor.RunResult run, String preview) {}

    private static String preview(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(65537);
            return new String(bytes, 0, Math.min(bytes.length, 65536), StandardCharsets.UTF_8)
                    + (bytes.length > 65536 ? "\n[미리보기는 64 KiB까지 표시합니다. 전체 응답은 파일을 확인하세요.]" : "");
        }
    }

    private static JTextArea textArea() {
        var area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setMargin(new java.awt.Insets(8, 8, 8, 8));
        return area;
    }

    private static JPanel section(String title, java.awt.Component content) {
        var panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }
}
