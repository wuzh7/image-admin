package org.dromara.order.store.service;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("local")
class StoreListingWorkbookTest {
    @Test
    void fillsPartialColumnsAndKeepsTemplateContent() throws Exception {
        var source = new ByteArrayOutputStream();
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("用户模板");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("商品层级");
            header.createCell(1).setCellValue("英文名称");
            header.createCell(2).setCellValue("轮播图2");
            header.createCell(3).setCellValue("库存计算");
            sheet.createRow(1).createCell(0).setCellValue("SPU");
            sheet.getRow(1).createCell(3).setCellFormula("1+1");
            sheet.getRow(1).setHeightInPoints(35);
            sheet.createRow(2).createCell(3).setCellValue("样板说明");
            sheet.createRow(3).createCell(0).setCellValue("SKU");
            sheet.getRow(3).createCell(3).setCellFormula("D2+1");
            sheet.createRow(4).createCell(3).setCellValue("保留尾部内容");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1, 1, 4, 5));
            workbook.write(source);
        }
        var output = new ByteArrayOutputStream();
        try (var workbook = StoreListingWorkbook.open(new ByteArrayInputStream(source.toByteArray()))) {
            workbook.fill(List.of(
                new StoreListingWorkbook.Product("101", "First", List.of("https://cdn.example/1.jpg", "https://cdn.example/2.jpg", "https://cdn.example/3.jpg")),
                new StoreListingWorkbook.Product("102", "Second", List.of("https://cdn.example/4.jpg", "https://cdn.example/5.jpg"))
            ), new StoreListingWorkbook.Profile(8, 1, 2, "-", "|", "both"));
            workbook.write(output);
        }
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("First", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("Second", sheet.getRow(4).getCell(1).getStringCellValue());
            assertEquals("https://cdn.example/2.jpg", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("https://cdn.example/5.jpg", sheet.getRow(6).getCell(2).getStringCellValue());
            assertEquals("D5+1", sheet.getRow(6).getCell(3).getCellFormula());
            assertEquals("样板说明", sheet.getRow(5).getCell(3).getStringCellValue());
            assertEquals("保留尾部内容", sheet.getRow(7).getCell(3).getStringCellValue());
            assertEquals(35, sheet.getRow(4).getHeightInPoints());
            assertEquals(2, sheet.getNumMergedRegions());
        }
    }

    @Test
    void missingColumnsAndSampleRowsDoNotRejectWorkbook() throws Exception {
        for (String header : List.of("英文名称", "用户自定义列")) {
            var source = new ByteArrayOutputStream();
            try (var workbook = new XSSFWorkbook()) {
                workbook.createSheet("用户模板").createRow(0).createCell(0).setCellValue(header);
                workbook.write(source);
            }
            var output = new ByteArrayOutputStream();
            try (var workbook = StoreListingWorkbook.open(new ByteArrayInputStream(source.toByteArray()))) {
                workbook.fill(List.of(new StoreListingWorkbook.Product("101", "First", List.of()),
                    new StoreListingWorkbook.Product("102", "Second", List.of())),
                    new StoreListingWorkbook.Profile(1, 1, 2, "-", "|", "both"));
                workbook.write(output);
            }
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
                var sheet = workbook.getSheetAt(0);
                assertEquals(header, sheet.getRow(0).getCell(0).getStringCellValue());
                if ("英文名称".equals(header)) assertEquals("Second", sheet.getRow(2).getCell(0).getStringCellValue());
                else assertEquals(0, sheet.getLastRowNum());
            }
        }
    }

    @Test
    void fillsTemplateWithoutSkuPreviewColumn() throws Exception {
        var source = new ByteArrayOutputStream();
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("模版");
            String[] headers = {"商品层级", "SPU货号", "商品名称", "英文名称", "SKU货号", "详情图文-英语", "商品轮播图1"};
            var header = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) header.createCell(c).setCellValue(headers[c]);
            sheet.createRow(1).createCell(0).setCellValue("spu");
            sheet.createRow(2).createCell(0).setCellValue("sku");
            workbook.write(source);
        }
        var output = new ByteArrayOutputStream();
        try (var workbook = StoreListingWorkbook.open(new ByteArrayInputStream(source.toByteArray()))) {
            workbook.fill(List.of(new StoreListingWorkbook.Product("101", "First title", List.of("https://cdn.example/1.jpg"))),
                new StoreListingWorkbook.Profile(1, 1, 2, "-", "|", "both"));
            workbook.write(output);
        }
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("First title", sheet.getRow(1).getCell(3).getStringCellValue());
            assertEquals("101-01", sheet.getRow(2).getCell(4).getStringCellValue());
            assertEquals("https://cdn.example/1.jpg", sheet.getRow(1).getCell(5).getStringCellValue());
            assertEquals("https://cdn.example/1.jpg", sheet.getRow(2).getCell(6).getStringCellValue());
            assertEquals(7, sheet.getRow(2).getLastCellNum());
        }
    }

    @Test
    void ignoresRepeatedDescriptionHeaderOutsideBusinessRow() throws Exception {
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("商品");
            var description = sheet.createRow(0);
            description.createCell(0).setCellValue("详情图文-英语");
            description.createCell(1).setCellValue("详情图文-英语");
            String[] headers = {"商品层级", "SPU货号", "商品名称", "英文名称", "SKU货号", "SKU预览图-英语", "详情图文-英语", "商品轮播图1"};
            var row = sheet.createRow(1);
            for (int column = 0; column < headers.length; column++) row.createCell(column).setCellValue(headers[column]);
            sheet.createRow(2).createCell(0).setCellValue("SPU");
            sheet.createRow(3).createCell(0).setCellValue("SKU");
            try (var input = new ByteArrayInputStream(toBytes(workbook));
                 StoreListingWorkbook parsed = StoreListingWorkbook.open(input)) {
                assertEquals(1, parsed.skuCount());
            }
        }
    }

    private static byte[] toBytes(XSSFWorkbook workbook) throws Exception {
        var output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }

    @Test
    void fillsReservedRowsWithoutChangingTemplateStyle() throws Exception {
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("商品");
            String[] headers = {"商品层级", "SPU货号", "商品名称", "英文名称", "SKU货号", "SKU预览图-英语", "详情图文-英语", "轮播图1"};
            var header = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) header.createCell(c).setCellValue(headers[c]);
            var style = workbook.createCellStyle(); style.setWrapText(true);
            var spu = sheet.createRow(1); spu.createCell(0).setCellValue("SPU"); spu.createCell(2).setCellStyle(style);
            var sku = sheet.createRow(2); sku.createCell(0).setCellValue("SKU");
            sheet.createRow(3); sheet.createRow(4);
            workbook.write(source);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (StoreListingWorkbook workbook = StoreListingWorkbook.open(new ByteArrayInputStream(source.toByteArray()))) {
            workbook.fill(List.of(
                new StoreListingWorkbook.Product("101", "First title", List.of("https://cdn.example/1.jpg")),
                new StoreListingWorkbook.Product("102", "Second title", List.of("https://cdn.example/2.jpg"))
            ), new StoreListingWorkbook.Profile(1, 1, 2, "-", "|", "both"));
            workbook.write(output);
        }
        try (XSSFWorkbook result = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            var sheet = result.getSheetAt(0);
            assertEquals("First title", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("101-01", sheet.getRow(2).getCell(4).getStringCellValue());
            assertEquals("Second title", sheet.getRow(3).getCell(2).getStringCellValue());
            assertEquals("102-01", sheet.getRow(4).getCell(4).getStringCellValue());
            assertTrue(sheet.getRow(3).getCell(2).getCellStyle().getWrapText());
        }
    }
}
