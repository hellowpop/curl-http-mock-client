package dev.curlmock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ConfigFilesTest {
    @TempDir Path temp;

    @Test void preservesAllSixtyScenariosAndResolvedPathsAcrossDirectories() throws Exception {
        Path source = temp.resolve("a/config.yml");
        Path excel = temp.resolve("b/config.xlsx");
        Path back = temp.resolve("c/config.yaml");
        ConfigFiles.write(source, ClientConfig.sample(), false);
        var expected = ConfigFiles.read(source);
        assertEquals(60, expected.payloadTypes().size());
        assertEquals(temp.resolve("a/results").toString(), expected.outputDirectory());
        ConfigFiles.convert(source, excel, false);
        ConfigFiles.convert(excel, back, false);
        assertEquals(expected, ConfigFiles.read(back));
        try (var book = new XSSFWorkbook(Files.newInputStream(excel))) {
            assertEquals(61, book.getSheet("PayloadTypes").getPhysicalNumberOfRows());
            assertEquals("key", book.getSheet("Settings").getRow(0).getCell(0).getStringCellValue());
        }
    }

    @Test void readsMinimalYamlWithDefaults() throws Exception {
        var config = read("endpointUrl: http://localhost:8080/base\npayloadTypes:\n  - contentType: json\n    transferEncoding: GZ\n    payloadSize: CM\n");
        assertEquals("POST", config.method());
        assertEquals(60, config.requestTimeoutSeconds());
        assertEquals("/CT_json/TE_GZ/PS_CM", config.payloadTypes().getFirst().path());
    }

    @Test void expandsCommaSeparatedValuesInDeclaredOrder() throws Exception {
        var config = read("endpointUrl: http://localhost:8080\npayloadTypes:\n  - contentType: json, xml\n    transferEncoding: GZ,CSB\n    payloadSize: CM,SM\n");
        assertEquals(List.of("/CT_json/TE_GZ/PS_CM", "/CT_json/TE_GZ/PS_SM", "/CT_json/TE_CSB/PS_CM", "/CT_json/TE_CSB/PS_SM",
                        "/CT_xml/TE_GZ/PS_CM", "/CT_xml/TE_GZ/PS_SM", "/CT_xml/TE_CSB/PS_CM", "/CT_xml/TE_CSB/PS_SM"),
                config.payloadTypes().stream().map(PayloadType::path).toList());
    }

    @Test void trimsTokensAndPreservesEntryOrderAndIntentionalDuplicates() throws Exception {
        var config = read("endpointUrl: http://localhost:8080\npayloadTypes:\n"
                + "  - {contentType: ' json , xml ', transferEncoding: ' GZ ', payloadSize: ' CM , SM '}\n"
                + "  - {contentType: form, transferEncoding: NA, payloadSize: SM}\n"
                + "  - {contentType: 'json, json', transferEncoding: CSB, payloadSize: SM}\n");
        assertEquals(List.of("/CT_json/TE_GZ/PS_CM", "/CT_json/TE_GZ/PS_SM", "/CT_xml/TE_GZ/PS_CM", "/CT_xml/TE_GZ/PS_SM",
                        "/CT_form/TE_NA/PS_SM", "/CT_json/TE_CSB/PS_SM", "/CT_json/TE_CSB/PS_SM"),
                config.payloadTypes().stream().map(PayloadType::path).toList());
    }

    @Test void expandsExcelCommaCellsAndPreservesBothConversionDirections() throws Exception {
        Path excel = temp.resolve("multi.xlsx");
        ConfigFiles.write(excel, ClientConfig.sample(), false);
        try (var book = new XSSFWorkbook(Files.newInputStream(excel))) {
            var sheet = book.getSheet("PayloadTypes");
            for (int i = sheet.getLastRowNum(); i > 1; i--) sheet.removeRow(sheet.getRow(i));
            var row = sheet.getRow(1);
            row.getCell(0).setCellValue("json, xml");
            row.getCell(1).setCellValue("GZ,CSB");
            row.getCell(2).setCellValue("CM,SM");
            try (var out = Files.newOutputStream(excel)) { book.write(out); }
        }
        var expected = read("endpointUrl: http://localhost:8080\npayloadTypes:\n  - contentType: json, xml\n    transferEncoding: GZ,CSB\n    payloadSize: CM,SM\n");
        assertEquals(expected, ConfigFiles.read(excel));
        Path yaml = temp.resolve("converted/multi.yml");
        ConfigFiles.convert(excel, yaml, false);
        assertEquals(expected, ConfigFiles.read(yaml));
        Path expanded = temp.resolve("back/multi.xlsx");
        ConfigFiles.convert(yaml, expanded, false);
        assertEquals(expected, ConfigFiles.read(expanded));
        try (var book = new XSSFWorkbook(Files.newInputStream(expanded))) {
            assertEquals(9, book.getSheet("PayloadTypes").getPhysicalNumberOfRows());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "contentType: 'json,'", "contentType: ',xml'", "contentType: 'json,,xml'", "contentType: 'json,unknown'",
        "transferEncoding: 'GZ,'", "transferEncoding: 'GZ, ,CSB'", "transferEncoding: 'GZ,1'", "transferEncoding: 'GZ,BAD'",
        "payloadSize: 'CM,'", "payloadSize: 'CM,,SM'", "payloadSize: 'CM,2'", "payloadSize: 'CM,BAD'"
    })
    void rejectsEmptyOrInvalidCommaTokens(String field) {
        String yaml = "endpointUrl: http://localhost:8080\npayloadTypes:\n  - contentType: json\n    transferEncoding: NA\n    payloadSize: SM\n";
        String key = field.substring(0, field.indexOf(':'));
        String original = switch (key) { case "contentType" -> "contentType: json"; case "transferEncoding" -> "transferEncoding: NA"; default -> "payloadSize: SM"; };
        assertThrows(IllegalArgumentException.class, () -> read(yaml.replace(original, field)));
    }

    @Test void rejectsUnknownFieldsInEntriesBeforeRunningCombinations() {
        assertThrows(Exception.class, () -> read("endpointUrl: http://localhost:8080\npayloadTypes:\n"
                + "  - {contentType: 'json,xml', transferEncoding: GZ, payloadSize: SM, unexpected: true}\n"));
    }

    @Test void readsCustomSizesMixedWithPresetsAndPreservesYamlExcelRoundTrip() throws Exception {
        var config = read("endpointUrl: http://localhost:8080\npayloadTypes:\n"
                + "  - {contentType: json, transferEncoding: GZ, payloadSize: 'CM,20K,1M'}\n");
        assertEquals(List.of("/CT_json/TE_GZ/PS_CM", "/CT_json/TE_GZ/PS_20K", "/CT_json/TE_GZ/PS_1M"),
                config.payloadTypes().stream().map(PayloadType::path).toList());
        Path excel = temp.resolve("sizes.xlsx");
        ConfigFiles.write(excel, config, false);
        assertEquals(config, ConfigFiles.read(excel));
        Path yaml = temp.resolve("converted/sizes.yml");
        ConfigFiles.convert(excel, yaml, false);
        assertEquals(config, ConfigFiles.read(yaml));
    }

    @Test void readsCommaSeparatedCustomSizesFromExcelCells() throws Exception {
        Path excel = temp.resolve("custom.xlsx");
        ConfigFiles.write(excel, ClientConfig.sample(), false);
        try (var book = new XSSFWorkbook(Files.newInputStream(excel))) {
            var sheet = book.getSheet("PayloadTypes");
            for (int i = sheet.getLastRowNum(); i > 1; i--) sheet.removeRow(sheet.getRow(i));
            sheet.getRow(1).getCell(2).setCellValue("20K,1M");
            try (var out = Files.newOutputStream(excel)) { book.write(out); }
        }
        var config = ConfigFiles.read(excel);
        assertEquals(List.of("/CT_json/TE_NA/PS_20K", "/CT_json/TE_NA/PS_1M"),
                config.payloadTypes().stream().map(PayloadType::path).toList());
        assertEquals(20480, config.payloadTypes().get(0).payloadSize().bytes());
        assertEquals(1048576, config.payloadTypes().get(1).payloadSize().bytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "endpointUrl: ftp://example.com\npayloadTypes: []",
        "endpointUrl: http://example.com?q=1\npayloadTypes: []",
        "endpointUrl: http://example.com\nunknown: 1\npayloadTypes: []",
        "endpointUrl: http://example.com\nendpointUrl: http://elsewhere.com\npayloadTypes: []",
        "endpointUrl: http://example.com\npayloadTypes: []",
        "endpointUrl: http://example.com\npayloadTypes: null",
        "endpointUrl: http://example.com\npayloadTypes: [null]",
        "endpointUrl: http://example.com\nconnectTimeoutSeconds: 0\npayloadTypes: [{contentType: json, transferEncoding: NA, payloadSize: SM}]",
        "endpointUrl: http://example.com\nrequestTimeoutSeconds: -1\npayloadTypes: [{contentType: json, transferEncoding: NA, payloadSize: SM}]",
        "endpointUrl: http://example.com\nmethod: DELETE\npayloadTypes: [{contentType: json, transferEncoding: NA, payloadSize: SM}]",
        "endpointUrl: http://example.com\npayloadTypes: [{contentType: json, transferEncoding: BAD, payloadSize: SM}]",
        "endpointUrl: http://example.com\npayloadTypes: [{contentType: xml, payloadSize: SM}]",
        "endpointUrl: http://example.com\nmethod: null\npayloadTypes: [{contentType: json, transferEncoding: NA, payloadSize: SM}]",
        "endpointUrl: http://example.com\nconnectTimeoutSeconds: 1.5\npayloadTypes: [{contentType: json, transferEncoding: NA, payloadSize: SM}]",
        "endpointUrl: http://example.com\npayloadTypes: [{contentType: json, transferEncoding: 1, payloadSize: SM}]",
        "endpointUrl: http://example.com\npayloadTypes: [{contentType: json, transferEncoding: NA, payloadSize: 2}]",
        "endpointUrl: http://example.com\npayloadTypes: [{contentType: json, transferEncoding: '2', payloadSize: SM}]",
        "endpointUrl: http://example.com\npayloadTypes: [{contentType: json, transferEncoding: NA, payloadSize: '1'}]",
        "endpointUrl: http://example.com\noutputDirectory: false\npayloadTypes: [{contentType: json, transferEncoding: NA, payloadSize: SM}]",
        "endpointUrl: http://example.com\ncurlExecutable: 123\npayloadTypes: [{contentType: json, transferEncoding: NA, payloadSize: SM}]",
        "endpointUrl: http://example.com\nconnectTimeoutSeconds: '5'\npayloadTypes: [{contentType: json, transferEncoding: NA, payloadSize: SM}]"
    })
    void rejectsMalformedYaml(String yaml) { assertThrows(Exception.class, () -> read(yaml)); }

    @Test void refusesOverwrite() throws Exception {
        Path path = temp.resolve("sample.yml");
        ConfigFiles.write(path, ClientConfig.sample(), false);
        String before = Files.readString(path);
        assertThrows(Exception.class, () -> ConfigFiles.write(path, ClientConfig.sample(), false));
        assertEquals(before, Files.readString(path));
        ConfigFiles.write(path, ClientConfig.sample(), true);
    }

    @Test void rejectsDuplicateExcelKeysAndFormulaCells() throws Exception {
        Path path = temp.resolve("bad.xlsx");
        ConfigFiles.write(path, ClientConfig.sample(), false);
        try (var book = new XSSFWorkbook(Files.newInputStream(path))) {
            var row = book.getSheet("Settings").createRow(9);
            row.createCell(0).setCellValue("endpointUrl");
            row.createCell(1).setCellValue("http://other.test");
            try (var out = Files.newOutputStream(path)) { book.write(out); }
        }
        assertThrows(Exception.class, () -> ConfigFiles.read(path));
        ConfigFiles.write(path, ClientConfig.sample(), true);
        try (var book = new XSSFWorkbook(Files.newInputStream(path))) {
            book.getSheet("PayloadTypes").getRow(1).getCell(0).setCellFormula("\"json\"");
            try (var out = Files.newOutputStream(path)) { book.write(out); }
        }
        assertThrows(Exception.class, () -> ConfigFiles.read(path));
    }

    @Test void rejectsMissingExcelSheetAndUnknownKey() throws Exception {
        Path path = temp.resolve("bad.xlsx");
        try (var book = new XSSFWorkbook()) {
            book.createSheet("Settings");
            try (var out = Files.newOutputStream(path)) { book.write(out); }
        }
        assertThrows(Exception.class, () -> ConfigFiles.read(path));
        ConfigFiles.write(path, ClientConfig.sample(), true);
        try (var book = new XSSFWorkbook(Files.newInputStream(path))) {
            book.getSheet("Settings").getRow(1).getCell(0).setCellValue("unknown");
            try (var out = Files.newOutputStream(path)) { book.write(out); }
        }
        assertThrows(Exception.class, () -> ConfigFiles.read(path));
    }

    @Test void rejectsExcelNumericEnumCells() throws Exception {
        Path path = temp.resolve("bad.xlsx");
        ConfigFiles.write(path, ClientConfig.sample(), false);
        try (var book = new XSSFWorkbook(Files.newInputStream(path))) {
            book.getSheet("PayloadTypes").getRow(1).getCell(1).setCellValue(1);
            try (var out = Files.newOutputStream(path)) { book.write(out); }
        }
        assertThrows(Exception.class, () -> ConfigFiles.read(path));
    }

    private ClientConfig read(String yaml) throws Exception {
        Path file = temp.resolve("config.yml");
        Files.writeString(file, yaml);
        return ConfigFiles.read(file);
    }
}
