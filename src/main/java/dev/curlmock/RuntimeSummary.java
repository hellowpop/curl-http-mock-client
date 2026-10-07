package dev.curlmock;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;

/** Editable draft of session settings; creates a validated snapshot without file I/O. */
final class RuntimeSummary extends JTable {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final DefaultTableModel values;

    RuntimeSummary() {
        values = new DefaultTableModel(new String[]{"항목", "실행값", "적용 범위"}, 0) {
            @Override public boolean isCellEditable(int row, int column) {
                return isEnabled() && column == 1 && row < 10;
            }
        };
        setModel(values);
        setRowHeight(26);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        getTableHeader().setReorderingAllowed(false);
        getColumnModel().getColumn(0).setPreferredWidth(150);
        getColumnModel().getColumn(1).setPreferredWidth(380);
        getColumnModel().getColumn(2).setPreferredWidth(90);
        putClientProperty("terminateEditOnFocusLost", true);
        setToolTipText("값을 편집한 뒤 업데이트 또는 실행을 누르세요. Curl arguments는 JSON 문자열 배열입니다.");
    }

    @Override public TableCellEditor getCellEditor(int row, int column) {
        if (column == 1) {
            if (row == 0) return new DefaultCellEditor(new JComboBox<>(new String[]{"POST", "PUT", "PATCH"}));
            if (row == 2) return new DefaultCellEditor(new JComboBox<>(java.util.Arrays.stream(ContentType.values()).map(Enum::name).toArray(String[]::new)));
            if (row == 3) return new DefaultCellEditor(new JComboBox<>(java.util.Arrays.stream(TransferEncoding.values()).map(Enum::name).toArray(String[]::new)));
        }
        return super.getCellEditor(row, column);
    }

    void show(ClientConfig config, PayloadType type) {
        if (isEditing()) getCellEditor().cancelCellEditing();
        values.setRowCount(0);
        if (type == null) return;
        add("Method", config.method(), "전체 요청");
        add("Endpoint URL", config.endpointUrl(), "전체 요청");
        add("Content-Type", type.contentType().name(), "선택 요청");
        add("Transfer-Encoding", type.transferEncoding().name(), "선택 요청");
        add("Payload", type.payloadSize().toString(), "선택 요청");
        add("Connect timeout (s)", type.effectiveConnectTimeoutSeconds(config), "선택 요청");
        add("Request timeout (s)", type.effectiveRequestTimeoutSeconds(config), "선택 요청");
        add("Curl", config.curlExecutable(), "전체 요청");
        add("Curl arguments", JSON.valueToTree(config.curlArguments()).toString(), "전체 요청");
        add("Output", config.outputDirectory(), "전체 요청");
        add("URL", config.endpointUrl().replaceAll("/+$", "") + type.path(), "자동 계산");
        add("Payload bytes", type.payloadSize().bytes(), "자동 계산");
    }

    ClientConfig updated(ClientConfig config, int index) {
        if (isEditing() && !getCellEditor().stopCellEditing())
            throw new IllegalArgumentException("셀 편집을 완료하세요.");
        var original = config.payloadTypes().get(index);
        int connect = Integer.parseInt(text(5));
        int request = Integer.parseInt(text(6));
        var type = new PayloadType(ContentType.parse(text(2)), TransferEncoding.parse(text(3)),
                PayloadSize.parse(text(4)), connect == original.effectiveConnectTimeoutSeconds(config)
                ? original.connectTimeoutSeconds() : Integer.valueOf(connect), request == original.effectiveRequestTimeoutSeconds(config)
                ? original.requestTimeoutSeconds() : Integer.valueOf(request));
        List<String> arguments = new ArrayList<>();
        try {
            var node = JSON.readTree(text(8));
            if (node == null || !node.isArray()) throw new IllegalArgumentException("Curl arguments는 JSON 문자열 배열이어야 합니다.");
            for (var arg : node) {
                if (!arg.isTextual()) throw new IllegalArgumentException("Curl arguments에는 문자열만 입력하세요.");
                arguments.add(arg.textValue());
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Curl arguments는 JSON 문자열 배열이어야 합니다.", e);
        }
        var types = new ArrayList<>(config.payloadTypes());
        types.set(index, type);
        var updated = new ClientConfig(text(1), text(0).toUpperCase(Locale.ROOT), text(7),
                config.connectTimeoutSeconds(), config.requestTimeoutSeconds(), text(9), List.copyOf(types), arguments);
        updated.validate();
        java.nio.file.Path.of(updated.outputDirectory());
        return updated;
    }

    private void add(String label, Object value, String scope) { values.addRow(new Object[]{label, value, scope}); }
    private String text(int row) { return String.valueOf(values.getValueAt(row, 1)).strip(); }
}
