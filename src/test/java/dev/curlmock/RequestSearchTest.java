package dev.curlmock;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class RequestSearchTest {
    @Test void filteredBatchButtonRequiresSearchResultsAndFooterButtonsAlignRight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new ApplicationPanel(config());
            assertFalse(panel.executeFiltered.isEnabled());
            panel.search.setText("json");
            assertTrue(panel.executeFiltered.isEnabled());
            panel.search.setText("json,missing");
            assertFalse(panel.executeFiltered.isEnabled());
            panel.search.setText(" , \t ");
            assertFalse(panel.executeFiltered.isEnabled());
            panel.search.setText("gz");
            assertTrue(panel.executeFiltered.isEnabled());
            panel.clearSearch.doClick();
            assertFalse(panel.executeFiltered.isEnabled());
            var footer = panel.executeAll.getParent();
            assertSame(footer, panel.executeFiltered.getParent());
            footer.setSize(330, 40);
            footer.doLayout();
            assertTrue(panel.executeFiltered.getX() < panel.executeAll.getX());
            assertEquals(325, panel.executeAll.getX() + panel.executeAll.getWidth());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"json gz", " JSON, GZ ", "gz,, \tjson", "gz json"})
    void allSpaceOrCommaSeparatedTokensMustMatch(String query) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new ApplicationPanel(config());
            panel.search.setText(query);
            assertEquals(1, panel.requests.getModel().getSize());
            assertEquals("/CT_json/TE_GZ/PS_LG", panel.requests.getSelectedValue().path());
            panel.search.setText(query + ",missing");
            assertEquals(0, panel.requests.getModel().getSize());
        });
    }

    @Test void separatorsAloneShowAllCasesWithoutHighlight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new ApplicationPanel(config());
            panel.search.setText(" , ,\t ");
            assertEquals(3, panel.requests.getModel().getSize());
            assertFalse(hasHighlight(panel, false));
        });
    }

    @Test void everyTokenIsHighlightedAndOverlappingMatchesDoNotDuplicateText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new ApplicationPanel(config());
            panel.requests.setSelectedIndex(2);
            panel.search.setText("json");
            int single = highlightPixels(panel, false);
            panel.search.setText("json, gz");
            assertTrue(highlightPixels(panel, false) > single, "both tokens must be highlighted");
            panel.search.setText("json son json");
            assertEquals(single, highlightPixels(panel, false), "overlapping and duplicate tokens share a highlight");
        });
    }

    private ClientConfig config() {
        return new ClientConfig("http://localhost:8080", "POST", "curl", 3, 3, "results", List.of(
                new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM),
                new PayloadType(ContentType.XML, TransferEncoding.GZ, PayloadSize.SM),
                new PayloadType(ContentType.JSON, TransferEncoding.GZ, PayloadSize.LG)));
    }

    @Test void typingFiltersImmediatelyAndClearRestoresOriginalCasesAndSelection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new ApplicationPanel(config());
            panel.requests.setSelectedIndex(2);
            panel.result.setText("previous result");
            panel.search.setText("  JsOn  ");
            assertEquals(2, panel.requests.getModel().getSize());
            assertEquals("/CT_json/TE_NA/PS_SM", panel.requests.getModel().getElementAt(0).path());
            assertEquals("/CT_json/TE_GZ/PS_LG", panel.requests.getSelectedValue().path());
            assertEquals("previous result", panel.result.getText());
            var cell = (JLabel) panel.requests.getCellRenderer().getListCellRendererComponent(
                    panel.requests, panel.requests.getSelectedValue(), 1, false, false);
            assertTrue(cell.getText().contains("3. "), "original case number must survive filtering");
            panel.clearSearch.doClick();
            assertEquals("", panel.search.getText());
            assertEquals(3, panel.requests.getModel().getSize());
            assertEquals(2, panel.requests.getSelectedIndex());
            assertFalse(panel.clearSearch.isEnabled());
        });
    }

    @Test void noMatchesClearsSelectionAndDisablesExecutionUntilSearchIsCancelled() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new ApplicationPanel(config());
            panel.search.setText("does-not-exist");
            assertEquals(0, panel.requests.getModel().getSize());
            assertNull(panel.requests.getSelectedValue());
            assertFalse(panel.execute.isEnabled());
            assertTrue(panel.summaryStatus.getText().contains("검색 결과가 없습니다"));
            panel.clearSearch.doClick();
            assertEquals(3, panel.requests.getModel().getSize());
            assertTrue(panel.execute.isEnabled());
            panel.search.setText("GZ/PS_LG");
            assertEquals(1, panel.requests.getModel().getSize());
            assertTrue(RuntimeSummaryTest.value(panel.summary, "URL").contains("/CT_json/TE_GZ/PS_LG"));
            panel.search.setText(".*");
            assertEquals(0, panel.requests.getModel().getSize(), "search is literal, not regex");
        });
    }

    @Test void identicalCasesKeepTheirOriginalSelectionAndNumber() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var original = config();
            var duplicates = new ClientConfig(original.endpointUrl(), original.method(), original.curlExecutable(),
                    3, 3, "results", List.of(original.payloadTypes().get(0), original.payloadTypes().get(1),
                    original.payloadTypes().get(0)));
            var panel = new ApplicationPanel(duplicates);
            panel.requests.setSelectedIndex(2);
            panel.search.setText("json");
            assertEquals(1, panel.requests.getSelectedIndex());
            panel.clearSearch.doClick();
            assertEquals(2, panel.requests.getSelectedIndex());
        });
    }

    @Test void matchingTextIsHighlightedAndClearingRemovesHighlight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new ApplicationPanel(config());
            panel.search.setText("JSON");
            assertTrue(hasHighlight(panel, false));
            assertTrue(hasHighlight(panel, true), "highlight must remain visible on selected rows");
            panel.clearSearch.doClick();
            assertFalse(hasHighlight(panel, false));
        });
    }

    private boolean hasHighlight(ApplicationPanel panel, boolean selected) {
        return highlightPixels(panel, selected) > 0;
    }

    private int highlightPixels(ApplicationPanel panel, boolean selected) {
        var cell = panel.requests.getCellRenderer().getListCellRendererComponent(panel.requests,
                panel.requests.getModel().getElementAt(0), 0, selected, false);
        cell.setSize(cell.getPreferredSize());
        var image = new BufferedImage(cell.getWidth(), cell.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        cell.paint(graphics);
        graphics.dispose();
        int highlighted = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            var color = new Color(image.getRGB(x, y));
            if (color.getRed() > 230 && color.getGreen() > 160 && color.getBlue() < 150) highlighted++;
        }
        return highlighted;
    }
}
