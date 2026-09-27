package org.dromara.order.store.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.dromara.common.core.exception.ServiceException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The uploaded sheet supplies the layout, sample SPU/SKU rows and reserved rows. */
final class StoreListingWorkbook implements AutoCloseable {
    private static final Map<String, List<String>> HEADERS = Map.of(
        "level", List.of("商品层级"), "spu", List.of("SPU货号", "SPU编号"),
        "titleZh", List.of("商品名称", "中文名称"), "titleEn", List.of("英文名称", "商品英文名称"),
        "sku", List.of("SKU货号", "SKU编号"), "preview", List.of("SKU预览图-英语", "SKU预览图（英语）"),
        "detail", List.of("详情图文-英语", "详情图文（英语）"));
    private static final Pattern CAROUSEL = Pattern.compile("^(?:商品|spu商品)?轮播图(\\d+)$");
    private final XSSFWorkbook workbook;
    private final Sheet sheet;
    private final Map<String, Integer> columns;
    private final List<Integer> carousel;
    private final int spuRow;
    private final List<Integer> skuRows;

    private StoreListingWorkbook(XSSFWorkbook workbook, Sheet sheet, Map<String, Integer> columns,
                                 List<Integer> carousel, int spuRow, List<Integer> skuRows) {
        this.workbook = workbook; this.sheet = sheet; this.columns = columns;
        this.carousel = carousel; this.spuRow = spuRow; this.skuRows = skuRows;
    }

    static StoreListingWorkbook open(InputStream input) throws IOException {
        XSSFWorkbook workbook = new XSSFWorkbook(input);
        try {
            if (workbook.isMacroEnabled()) throw new ServiceException("不支持带宏的工作簿");
            StoreListingWorkbook result = null;
            DataFormatter formatter = new DataFormatter();
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                if (workbook.isSheetHidden(s) || workbook.isSheetVeryHidden(s)) continue;
                Sheet sheet = workbook.getSheetAt(s);
                if (sheet.getSheetName().matches("(?i).*(示例|example|指南|guide).*$")) continue;
                for (int i = 0; i <= Math.min(99, sheet.getLastRowNum()); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;
                    Map<String, Integer> found = new HashMap<>();
                    List<Integer> carousel = new ArrayList<>();
                    Map<Integer, Integer> carouselByIndex = new HashMap<>();
                    for (Cell cell : row) {
                        String header = normalize(formatter.formatCellValue(cell));
                        for (var entry : HEADERS.entrySet()) if (entry.getValue().stream().map(StoreListingWorkbook::normalize).anyMatch(header::equals)) {
                            found.putIfAbsent(entry.getKey(), cell.getColumnIndex());
                        }
                        Matcher match = CAROUSEL.matcher(header);
                        if (match.matches()) {
                            int index = Integer.parseInt(match.group(1));
                            carouselByIndex.putIfAbsent(index, cell.getColumnIndex());
                        }
                    }
                    if (found.size() != HEADERS.size() || carouselByIndex.isEmpty()) continue;
                    for (int n = 1; n <= carouselByIndex.size(); n++) {
                        Integer col = carouselByIndex.get(n);
                        if (col == null) throw new ServiceException("轮播图列必须从 1 连续编号");
                        carousel.add(col);
                    }
                    List<Integer> rows = new ArrayList<>();
                    for (int n = i + 1; n <= sheet.getLastRowNum(); n++) {
                        Row data = sheet.getRow(n);
                        if (data == null) continue;
                        String level = normalize(formatter.formatCellValue(data.getCell(found.get("level"))));
                        if ("spu".equals(level) || "sku".equals(level)) rows.add(n);
                    }
                    if (rows.size() < 2 || rows.size() > 101 || !"spu".equals(normalize(formatter.formatCellValue(sheet.getRow(rows.get(0)).getCell(found.get("level"))))))
                        throw new ServiceException("模板需要 1 行 SPU 样板及 1 至 100 行 SKU 样板");
                    for (int n = 1; n < rows.size(); n++) {
                        if (rows.get(n) != rows.get(n - 1) + 1 || !"sku".equals(normalize(formatter.formatCellValue(sheet.getRow(rows.get(n)).getCell(found.get("level"))))))
                            throw new ServiceException("SPU/SKU 样板行必须连续且仅有一组");
                    }
                    int lastSample = rows.get(rows.size() - 1);
                    for (int n = rows.get(0); n <= sheet.getLastRowNum(); n++) {
                        Row data = sheet.getRow(n);
                        if (data == null) continue;
                        for (Cell cell : data) {
                            if (n <= lastSample && cell.getCellType() == CellType.FORMULA) throw new ServiceException("样板行包含公式，无法安全复制");
                            if (n > lastSample && !formatter.formatCellValue(cell).isBlank()) throw new ServiceException("样板商品下方还有内容，请使用只含一组样板的空表");
                        }
                    }
                    for (var merge : sheet.getMergedRegions()) if (merge.getLastRow() >= rows.get(0)) throw new ServiceException("商品数据区不能有合并单元格");
                    if (result != null) throw new ServiceException("识别到多个商品工作表，请只保留一个待填模板");
                    result = new StoreListingWorkbook(workbook, sheet, found, carousel, rows.get(0), rows.subList(1, rows.size()));
                    break;
                }
            }
            if (result == null) throw new ServiceException("未识别到 SPU/SKU 表头和样板行");
            return result;
        } catch (RuntimeException exception) {
            workbook.close();
            throw exception;
        }
    }

    int carouselCount() { return carousel.size(); }
    int skuCount() { return skuRows.size(); }
    int capacity() { return sheet.getLastRowNum() - spuRow + 1; }

    void fill(List<Product> products, Profile profile) {
        int rowsPerProduct = skuRows.size() + 1;
        if ((long) products.size() * rowsPerProduct > capacity()) throw new ServiceException("上架表预留行不足，请扩展模板后重新上传");
        List<List<SeedCell>> seeds = new ArrayList<>();
        List<Integer> sourceRows = new ArrayList<>(); sourceRows.add(spuRow); sourceRows.addAll(skuRows);
        for (Integer index : sourceRows) {
            List<SeedCell> cells = new ArrayList<>();
            for (Cell cell : sheet.getRow(index)) cells.add(new SeedCell(cell.getColumnIndex(), cell.getCellStyle(), cell.getCellType(),
                switch (cell.getCellType()) {
                    case STRING -> cell.getStringCellValue();
                    case NUMERIC -> cell.getNumericCellValue();
                    case BOOLEAN -> cell.getBooleanCellValue();
                    default -> null;
                }));
            seeds.add(cells);
        }
        for (int p = 0; p < products.size(); p++) {
            Product product = products.get(p);
            if (product.urls().size() > carousel.size()) throw new ServiceException("模板轮播图列不足，无法容纳全部套图");
            for (int r = 0; r < rowsPerProduct; r++) {
                int targetIndex = spuRow + p * rowsPerProduct + r;
                Row target = sheet.getRow(targetIndex);
                if (target == null) throw new ServiceException("模板预留区缺少连续空行，请在 Excel 中补齐");
                for (SeedCell source : seeds.get(r)) {
                    Cell dest = target.getCell(source.column(), Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    dest.setCellStyle(source.style());
                    if (source.type() == CellType.STRING) dest.setCellValue((String) source.value());
                    else if (source.type() == CellType.NUMERIC) dest.setCellValue((Double) source.value());
                    else if (source.type() == CellType.BOOLEAN) dest.setCellValue((Boolean) source.value());
                    else dest.setBlank();
                }
                boolean spu = r == 0;
                set(target, "spu", product.sourceId());
                set(target, "titleZh", spu ? product.title() : "");
                set(target, "titleEn", spu ? product.title() : "");
                set(target, "sku", spu ? "" : product.sourceId() + profile.skuSeparator() + String.format("%0" + profile.skuDigits() + "d", profile.skuStart() + r - 1));
                set(target, "preview", spu ? "" : product.urls().get(profile.previewIndex() - 1));
                set(target, "detail", spu ? String.join(profile.detailSeparator(), product.urls()) : "");
                for (int c = 0; c < carousel.size(); c++) {
                    target.getCell(carousel.get(c), Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)
                        .setCellValue((spu || "both".equals(profile.carouselScope())) && c < product.urls().size() ? product.urls().get(c) : "");
                }
            }
        }
    }

    private void set(Row row, String key, String value) {
        row.getCell(columns.get(key), Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(value);
    }

    void write(OutputStream output) throws IOException { workbook.write(output); }
    @Override public void close() throws IOException { workbook.close(); }
    private static String normalize(String text) { return text == null ? "" : text.replaceAll("[\\s*＊]", "").toLowerCase(Locale.ROOT); }
    record Product(String sourceId, String title, List<String> urls) {}
    record Profile(int previewIndex, int skuStart, int skuDigits, String skuSeparator, String detailSeparator, String carouselScope) {}
    private record SeedCell(int column, CellStyle style, CellType type, Object value) {}
}
