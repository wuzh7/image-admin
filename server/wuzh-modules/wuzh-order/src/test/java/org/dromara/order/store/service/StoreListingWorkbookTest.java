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
