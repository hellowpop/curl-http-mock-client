package dev.curlmock;

import java.util.List;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

final class ExcelStyles {
    private ExcelStyles() {}
    static void header(Workbook book, Sheet sheet, List<String> labels) {
        var style = book.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        var font = book.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        Row row = sheet.createRow(0);
        row.setHeightInPoints(24);
        for (int i = 0; i < labels.size(); i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(labels.get(i));
            cell.setCellStyle(style);
        }
        sheet.createFreezePane(0, 1);
    }
    static void filter(Sheet sheet) {
        sheet.setAutoFilter(new CellRangeAddress(0, sheet.getLastRowNum(), 0, sheet.getRow(0).getLastCellNum() - 1));
    }
}
