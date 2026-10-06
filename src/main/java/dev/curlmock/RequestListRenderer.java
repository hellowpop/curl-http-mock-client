package dev.curlmock;

import java.awt.Component;
import java.util.Locale;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;

/** Keeps original case numbers and highlights every literal match, including selected rows. */
final class RequestListRenderer extends DefaultListCellRenderer {
    private final Supplier<String> keyword;
    private final IntUnaryOperator caseNumber;

    RequestListRenderer(Supplier<String> keyword, IntUnaryOperator caseNumber) {
        this.keyword = keyword;
        this.caseNumber = caseNumber;
    }

    @Override public Component getListCellRendererComponent(JList<?> list, Object value,
            int index, boolean selected, boolean focus) {
        String path = ((PayloadType) value).path();
        var tokens = RequestSearch.tokens(keyword.get());
        String label = caseNumber.applyAsInt(index) + ". ";
        if (tokens.isEmpty()) label += path;
        else {
            var html = new StringBuilder("<html>").append(label);
            String lower = path.toLowerCase(Locale.ROOT);
            boolean[] highlighted = new boolean[path.length()];
            for (String token : tokens) {
                int match = lower.indexOf(token);
                while (match >= 0) {
                    java.util.Arrays.fill(highlighted, match, match + token.length(), true);
                    match = lower.indexOf(token, match + 1);
                }
            }
            for (int start = 0; start < path.length();) {
                int end = start + 1;
                while (end < path.length() && highlighted[end] == highlighted[start]) end++;
                if (highlighted[start]) html.append("<span style='background-color:#ffe082;color:#202020;'>");
                html.append(escape(path.substring(start, end)));
                if (highlighted[start]) html.append("</span>");
                start = end;
            }
            label = html.append("</html>").toString();
        }
        return super.getListCellRendererComponent(list, label, index, selected, focus);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
