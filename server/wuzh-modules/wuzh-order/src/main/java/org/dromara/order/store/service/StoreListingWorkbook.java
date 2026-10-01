package org.dromara.order.store.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
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
    private final XSSFSheet sheet;
    private final Map<String, Integer> columns;
    private final Map<Integer, Integer> carousel;
    private final int spuRow;
    private final List<Integer> skuRows;

    private StoreListingWorkbook(XSSFWorkbook workbook, XSSFSheet sheet, Map<String, Integer> columns,
                                 Map<Integer, Integer> carousel, int spuRow, List<Integer> skuRows) {
        this.workbook = workbook; this.sheet = sheet; this.columns = columns;
        this.carousel = carousel; this.spuRow = spuRow; this.skuRows = skuRows;
    }

    static StoreListingWorkbook open(InputStream input) throws IOException {
        XSSFWorkbook workbook = new XSSFWorkbook(input);
        if (workbook.isMacroEnabled()) {
            workbook.close();
            throw new ServiceException("不支持带宏的工作簿");
        }
        StoreListingWorkbook result = null;
        int bestScore = 0;
        DataFormatter formatter = new DataFormatter();
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            if (workbook.isSheetHidden(s) || workbook.isSheetVeryHidden(s)) continue;
            XSSFSheet sheet = workbook.getSheetAt(s);
            if (sheet.getSheetName().matches("(?i).*(示例|example|指引|指南|guide).*$")) continue;
            for (Row row : sheet) {
                Map<String, Integer> found = new HashMap<>();
                Map<Integer, Integer> carousel = new TreeMap<>();
                for (Cell cell : row) {
                    String header = normalize(formatter.formatCellValue(cell));
                    if (header.isEmpty()) continue;
                    for (var entry : HEADERS.entrySet()) if (entry.getValue().stream().map(StoreListingWorkbook::normalize).anyMatch(header::equals))
                        found.putIfAbsent(entry.getKey(), cell.getColumnIndex());
                    Matcher match = CAROUSEL.matcher(header);
                    if (match.matches()) carousel.putIfAbsent(Integer.parseInt(match.group(1)), cell.getColumnIndex());
                }
                int score = found.size() + carousel.size();
                if (score <= bestScore) continue;
                bestScore = score;
                int spuRow = row.getRowNum() + 1;
                List<Integer> skuRows = new ArrayList<>();
                if (found.containsKey("level")) {
                    boolean started = false;
                    for (int n = spuRow; n <= sheet.getLastRowNum(); n++) {
                        Row data = sheet.getRow(n);
                        if (data == null) continue;
                        String level = normalize(formatter.formatCellValue(data.getCell(found.get("level"))));
                        if ("spu".equals(level)) {
                            if (started) break;
                            spuRow = n; started = true;
                        } else if (started && "sku".equals(level)) skuRows.add(n);
                    }
                }
                result = new StoreListingWorkbook(workbook, sheet, found, carousel, spuRow, skuRows);
            }
        }
        return result != null ? result : new StoreListingWorkbook(workbook, null, Map.of(), Map.of(), 0, List.of());
    }

    int skuCount() { return skuRows.size(); }

    void fill(List<Product> products, Profile profile) {
        if (sheet == null || products.isEmpty()) return;
        int rowsPerProduct = skuRows.size() + 1;
        int blockSize = (skuRows.isEmpty() ? spuRow : skuRows.get(skuRows.size() - 1)) - spuRow + 1;
        List<Integer> sourceRows = new ArrayList<>(); sourceRows.add(spuRow); sourceRows.addAll(skuRows);
        int lastSample = spuRow + blockSize - 1;
        if (products.size() > 1 && sheet.getLastRowNum() > lastSample)
            sheet.shiftRows(lastSample + 1, sheet.getLastRowNum(), (products.size() - 1) * blockSize);
        if (sheet.getRow(spuRow) == null) sheet.createRow(spuRow);
        for (int p = 1; p < products.size(); p++)
            sheet.copyRows(spuRow, lastSample, spuRow + p * blockSize, new CellCopyPolicy());
        for (int p = 0; p < products.size(); p++) {
            Product product = products.get(p);
            for (int r = 0; r < rowsPerProduct; r++) {
                int targetIndex = sourceRows.get(r) + p * blockSize;
                Row target = sheet.getRow(targetIndex);
                if (target == null) target = sheet.createRow(targetIndex);
                boolean spu = r == 0;
                set(target, "spu", product.sourceId());
                set(target, "titleZh", spu ? product.title() : "");
                set(target, "titleEn", spu ? product.title() : "");
                set(target, "sku", spu ? "" : product.sourceId() + profile.skuSeparator() + String.format("%0" + profile.skuDigits() + "d", profile.skuStart() + r - 1));
                set(target, "preview", spu || profile.previewIndex() > product.urls().size() ? "" : product.urls().get(profile.previewIndex() - 1));
                set(target, "detail", spu ? String.join(profile.detailSeparator(), product.urls()) : "");
                for (var entry : carousel.entrySet()) {
                    int c = entry.getKey() - 1;
                    target.getCell(entry.getValue(), Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)
                        .setCellValue((spu || "both".equals(profile.carouselScope())) && c >= 0 && c < product.urls().size() ? product.urls().get(c) : "");
                }
            }
        }
    }

    private void set(Row row, String key, String value) {
        Integer column = columns.get(key);
        if (column != null) row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(value);
    }

    void write(OutputStream output) throws IOException { workbook.write(output); }
    @Override public void close() throws IOException { workbook.close(); }
    private static String normalize(String text) { return text == null ? "" : text.replaceAll("[\\s*＊]", "").toLowerCase(Locale.ROOT); }
    record Product(String sourceId, String title, List<String> urls) {}
    record Profile(int previewIndex, int skuStart, int skuDigits, String skuSeparator, String detailSeparator, String carouselScope) {}
}
