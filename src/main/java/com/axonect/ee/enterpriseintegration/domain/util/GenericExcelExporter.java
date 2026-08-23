//package com.axonect.ee.enterpriseintegration.domain.util;
//
//import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.poi.ss.usermodel.*;
//import org.apache.poi.xssf.usermodel.XSSFSheet;
//import org.apache.poi.xssf.usermodel.XSSFWorkbook;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Component;
//
//import java.io.FileOutputStream;
//import java.io.IOException;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.nio.file.Paths;
//import java.text.SimpleDateFormat;
//import java.util.Date;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//
//
//@Component
//@Slf4j
//public class GenericExcelExporter {
//
//    @Value("${report.output.directory}")
//    private String outputDirectory;
//
//    private final Map<Path, WorkbookContext> workbookStore = new ConcurrentHashMap<>();
//
//
//    // ── Public API ────────────────────────────────────────────────────────────
//
//    public Path createExcelFileWithHeaders(
//            String reportName,
//            String classificationLevel,
//            List<CsvColumn> columns
//    ) throws IOException {
//
//        Path directory = Paths.get(outputDirectory);
//        if (!Files.exists(directory)) {
//            Files.createDirectories(directory);
//        }
//
//        String timestamp = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date());
//        Path filePath = directory.resolve(reportName + "_" + timestamp + ".xlsx");
//
//        XSSFWorkbook workbook = new XSSFWorkbook();
//        XSSFSheet    sheet    = workbook.createSheet("Report");
//
//        // ── Header row ────────────────────────────────────────────────────────
//        CellStyle headerStyle = buildHeaderStyle(workbook);
//        Row headerRow = sheet.createRow(0);
//        headerRow.setHeightInPoints(20);
//        for (int i = 0; i < columns.size(); i++) {
//            Cell cell = headerRow.createCell(i);
//            cell.setCellValue(columns.get(i).getLabel());
//            cell.setCellStyle(headerStyle);
//        }
//
//        sheet.createFreezePane(0, 1);
//
//        workbookStore.put(filePath, new WorkbookContext(workbook, sheet, 1));
//        log.info("Excel report initialized at {}", filePath);
//        return filePath;
//    }
//
//    public void appendRowsToExcel(
//            Path filePath,
//            List<CsvColumn> columns,
//            List<Map<String, Object>> rows
//    ) {
//        WorkbookContext ctx = workbookStore.get(filePath);
//        if (ctx == null) {
//            throw new IllegalStateException("Workbook not initialized for " + filePath);
//        }
//
//        for (Map<String, Object> rowData : rows) {
//            Row row = ctx.sheet.createRow(ctx.currentRow++);
//            int colIndex = 0;
//            for (CsvColumn column : columns) {
//                Object value = rowData.get(column.getKey());
//                row.createCell(colIndex++).setCellValue(value != null ? value.toString() : "");
//            }
//        }
//    }
//
//    public void saveAndClose(Path filePath) throws IOException {
//        WorkbookContext ctx = workbookStore.remove(filePath);
//        if (ctx == null) return;
//
//        try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
//            ctx.workbook.write(fos);
//        } finally {
//            ctx.workbook.close();
//
//        }
//        log.info("Excel report finalized at {}", filePath);
//    }
//
//
//    // ── Cell styles ───────────────────────────────────────────────────────────
//
//    private CellStyle buildHeaderStyle(Workbook workbook) {
//        CellStyle style = workbook.createCellStyle();
//        Font font = workbook.createFont();   // resolves to org.apache.poi.ss.usermodel.Font
//        font.setBold(true);
//        font.setFontName("Arial");
//        font.setFontHeightInPoints((short) 10);
//        font.setColor(IndexedColors.WHITE.getIndex());
//        style.setFont(font);
//        style.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
//        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
//        style.setAlignment(HorizontalAlignment.CENTER);
//        style.setVerticalAlignment(VerticalAlignment.CENTER);
//        setBorder(style, BorderStyle.THIN, IndexedColors.GREY_25_PERCENT);
//        return style;
//    }
//
//
//    private void setBorder(CellStyle style, BorderStyle borderStyle, IndexedColors color) {
//        style.setBorderTop(borderStyle);
//        style.setBorderBottom(borderStyle);
//        style.setBorderLeft(borderStyle);
//        style.setBorderRight(borderStyle);
//        style.setTopBorderColor(color.getIndex());
//        style.setBottomBorderColor(color.getIndex());
//        style.setLeftBorderColor(color.getIndex());
//        style.setRightBorderColor(color.getIndex());
//    }
//
//
//    // ── Inner class ───────────────────────────────────────────────────────────
//
//    private static class WorkbookContext {
//        final Workbook  workbook;
//        final XSSFSheet sheet;
//        int currentRow;
//
//        WorkbookContext(Workbook workbook, XSSFSheet sheet, int currentRow) {
//            this.workbook   = workbook;
//            this.sheet      = sheet;
//            this.currentRow = currentRow;
//        }
//    }
//}

package com.axonect.ee.enterpriseintegration.domain.util;

import com.axonect.ee.enterpriseintegration.application.transport.response.CsvColumn;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class GenericExcelExporter {

    @Value("${report.output.directory}")
    private String outputDirectory;

    // Row window: only 100 rows held in memory at a time
    private static final int ROW_ACCESS_WINDOW = 100;

    private final Map<Path, WorkbookContext> workbookStore = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    public Path createExcelFileWithHeaders(
            String reportName,
            String classificationLevel,
            List<CsvColumn> columns
    ) throws IOException {

        Path directory = Paths.get(outputDirectory);
        if (!Files.exists(directory)) {
            Files.createDirectories(directory);
        }

        String timestamp = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date());
        Path filePath = directory.resolve(reportName + "_" + timestamp + ".xlsx");

        // ✅ SXSSF with bounded row window + compressed temp files
        SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_ACCESS_WINDOW);
        workbook.setCompressTempFiles(true);

        SXSSFSheet sheet = workbook.createSheet("Report");
        sheet.trackAllColumnsForAutoSizing(); // optional, only if you need autoSizeColumn later

        // ── Header row ────────────────────────────────────────────────────────
        CellStyle headerStyle = buildHeaderStyle(workbook);
        Row headerRow = sheet.createRow(0);
        headerRow.setHeightInPoints(20);
        for (int i = 0; i < columns.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(columns.get(i).getLabel());
            cell.setCellStyle(headerStyle);
        }

        sheet.createFreezePane(0, 1);

        workbookStore.put(filePath, new WorkbookContext(workbook, sheet, 1, headerStyle));
        log.info("Excel report initialized at {}", filePath);
        return filePath;
    }

    public void appendRowsToExcel(
            Path filePath,
            List<CsvColumn> columns,
            List<Map<String, Object>> rows
    ) {
        WorkbookContext ctx = workbookStore.get(filePath);
        if (ctx == null) {
            throw new IllegalStateException("Workbook not initialized for " + filePath);
        }

        CellStyle dataStyle = ctx.dataStyle; // ✅ reuse pre-created style, never create per-row

        for (Map<String, Object> rowData : rows) {
            Row row = ctx.sheet.createRow(ctx.currentRow++);
            int colIndex = 0;
            for (CsvColumn column : columns) {
                Object value = rowData.get(column.getKey());
                Cell cell = row.createCell(colIndex++);
                cell.setCellValue(value != null ? value.toString() : "");
                cell.setCellStyle(dataStyle);
            }
        }
    }

    public void saveAndClose(Path filePath) throws IOException {
        WorkbookContext ctx = workbookStore.remove(filePath);
        if (ctx == null) return;

        try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
            ctx.workbook.write(fos);
        } finally {
            try {
                ctx.workbook.close();
            } catch (IOException e) {
                log.warn("Failed to close workbook for {}", filePath, e);
            }
            // ✅ CRITICAL: deletes SXSSF temp XML files from disk
            ctx.workbook.dispose();
        }

        log.info("Excel report finalized at {}", filePath);
    }

    /**
     * Safety net: call this if an error occurs upstream and saveAndClose
     * will never be reached. Prevents permanent leaks in workbookStore.
     */
    public void abort(Path filePath) {
        WorkbookContext ctx = workbookStore.remove(filePath);
        if (ctx == null) return;
        try {
            ctx.workbook.close();
        } catch (IOException e) {
            log.warn("Failed to close workbook during abort for {}", filePath, e);
        } finally {
            ctx.workbook.dispose();
        }
        log.warn("Aborted and cleaned up workbook for {}", filePath);
    }

    // ── Cell styles ───────────────────────────────────────────────────────────

    private CellStyle buildHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontName("Arial");
        font.setFontHeightInPoints((short) 10);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorder(style, BorderStyle.THIN, IndexedColors.GREY_25_PERCENT);
        return style;
    }

    private CellStyle buildDataStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorder(style, BorderStyle.THIN, IndexedColors.GREY_25_PERCENT);
        return style;
    }

    private void setBorder(CellStyle style, BorderStyle borderStyle, IndexedColors color) {
        style.setBorderTop(borderStyle);
        style.setBorderBottom(borderStyle);
        style.setBorderLeft(borderStyle);
        style.setBorderRight(borderStyle);
        style.setTopBorderColor(color.getIndex());
        style.setBottomBorderColor(color.getIndex());
        style.setLeftBorderColor(color.getIndex());
        style.setRightBorderColor(color.getIndex());
    }

    // ── Inner class ───────────────────────────────────────────────────────────

    private static class WorkbookContext {
        final SXSSFWorkbook workbook;
        final SXSSFSheet sheet;
        final CellStyle dataStyle;  // ✅ created once, reused across all rows
        int currentRow;

        WorkbookContext(SXSSFWorkbook workbook, SXSSFSheet sheet, int currentRow, CellStyle dataStyle) {
            this.workbook   = workbook;
            this.sheet      = sheet;
            this.currentRow = currentRow;
            this.dataStyle  = dataStyle;
        }
    }
}