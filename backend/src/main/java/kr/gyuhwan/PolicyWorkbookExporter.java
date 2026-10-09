package kr.gyuhwan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;

// 라이브러리 없이 OOXML ZIP을 작성한다. 가격 정책 시트는 편집·재업로드용,
// 모델 목록 시트는 전체 모델 확인용이다. 가져오기 파서는 PolicyImportService에 있다.
@Component
public class PolicyWorkbookExporter {
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String[] CARRIERS = {"SKT", "KT", "LG U+"};
    private static final String[] JOIN_TYPES = {"번호이동", "기기변경", "신규가입"};
    private static final String[] DISCOUNTS = {"common", "choice"};
    private static final DateTimeFormatter SAVED_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneId.of("Asia/Seoul"));
    static final String[] POLICY_HEADERS = {
            "ID", "제조사", "모델명", "모델코드", "네트워크", "통신사", "가입 유형", "할인 방법", "등록 상태",
            "출고가 (원)", "공통 지원금 (원)", "추가 지원금 (원)", "지원금 문의", "할부이자 총액 (원)",
            "요금제명", "요금제 월정액 (원)", "선택약정 할인 대상 (원)", "부가서비스 월요금 (원)",
            "부가서비스 기간 (개월)", "부가서비스 1~24개월 금액 (JSON, 원)",
            "요금제 안내", "부가서비스 안내", "위약금 안내", "구매 조건 / 안내"
    };
    private static final double[] POLICY_WIDTHS = {
            9, 12, 27, 25, 13, 12, 13, 21, 12, 18, 21, 21, 14, 23, 30, 24, 28, 26, 24, 58, 48, 48, 48, 52
    };
    static final String[] MODEL_HEADERS = {
            "ID", "제조사", "모델명", "모델코드", "네트워크", "등록 색상", "색상 기준",
            "기본 이미지 경로", "SKT 모델코드", "KT 모델코드", "LG U+ 모델코드",
            "SKT 이미지 경로", "KT 이미지 경로", "LG U+ 이미지 경로"
    };
    private static final double[] MODEL_WIDTHS = {9, 12, 27, 25, 13, 58, 16, 48, 25, 25, 25, 48, 48, 48};
    private final SiteContentService content;
    private final JsonNode defaultColors;

    public PolicyWorkbookExporter(ObjectMapper mapper, SiteContentService content) throws IOException {
        this.content = content;
        defaultColors = resource(mapper, "/model-colors.json");
    }

    private static JsonNode resource(ObjectMapper mapper, String path) throws IOException {
        try (InputStream input = PolicyWorkbookExporter.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing workbook resource: " + path);
            return mapper.readTree(input);
        }
    }

    // 한 가격 저장본과 현재 모델 콘텐츠를 받아 모든 모델×통신사×가입유형×할인유형을 행으로 출력한다.
    // 미등록 조건도 행으로 남겨 운영자가 누락 여부를 확인하고 입력할 수 있다.
    public byte[] export(Map<String, Object> snapshot) throws IOException {
        JsonNode products = content.resource("products.json");
        JsonNode config = (JsonNode) snapshot.get("config");
        String metadata = "저장 버전: " + snapshot.get("revision") + " | 저장 시각 (KST): "
                + savedTime(String.valueOf(snapshot.get("updatedAt")));
        List<List<Cell>> policies = new ArrayList<>();
        List<List<Cell>> models = new ArrayList<>();
        for (JsonNode product : products) {
            String id = product.path("id").asText();
            for (String carrier : CARRIERS) {
                for (String joinType : JOIN_TYPES) {
                    for (String discount : DISCOUNTS) {
                        JsonNode offer = config.path("offers").path(id + "|" + carrier + "|" + joinType + "|" + discount);
                        List<Cell> row = identifiers(product);
                        row.add(text(carrier));
                        row.add(text(joinType));
                        row.add(text(discount));
                        row.add(text(offer.isObject() ? "등록" : "미등록"));
                        row.add(amount(offer.path("retail")));
                        row.add(amount(offer.path("common")));
                        row.add(amount(offer.path("support")));
                        row.add(text(offer.path("supportInquiry").isBoolean()
                                ? (offer.path("supportInquiry").asBoolean() ? "예" : "아니오") : ""));
                        row.add(amount(offer.path("installmentFee")));
                        row.add(text(value(offer.path("planName"))));
                        row.add(amount(offer.path("planFee")));
                        row.add(amount(offer.path("discountBase")));
                        row.add(amount(offer.path("addonFee")));
                        row.add(number(offer.path("addonMonths"), 0));
                        row.add(text(offer.path("addonSchedule").isArray() ? offer.path("addonSchedule").toString() : ""));
                        row.add(text(value(offer.path("planDetails"))));
                        row.add(text(value(offer.path("addonDetails"))));
                        row.add(text(value(offer.path("penaltyDetails"))));
                        row.add(text(value(offer.path("terms"))));
                        policies.add(row);
                    }
                }
            }
            List<Cell> model = identifiers(product);
            JsonNode configured = config.path("colors").path(id);
            boolean useConfigured = configured.isArray() && !configured.isEmpty();
            JsonNode colors = useConfigured ? configured : defaultColors.path(id).path("colors");
            List<String> names = new ArrayList<>();
            if (colors.isArray()) colors.forEach(color -> names.add(color.asText()));
            model.add(text(String.join(" / ", names)));
            model.add(text(useConfigured ? "현재 저장값" : names.isEmpty() ? "등록 없음" : "기본 색상"));
            model.add(text(value(product.path("image"))));
            for (String carrier : CARRIERS) model.add(text(value(product.path("carrierCodes").path(carrier))));
            for (String carrier : CARRIERS) model.add(text(value(product.path("carrierImages").path(carrier))));
            models.add(model);
        }
        // Excel이 요구하는 워크북·관계·스타일·시트 XML을 메모리에서 ZIP으로 묶는다.
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", contentTypes());
            entry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>
                    """);
            entry(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><bookViews><workbookView/></bookViews><sheets><sheet name="가격 정책" sheetId="1" r:id="rId1"/><sheet name="모델 목록" sheetId="2" r:id="rId2"/></sheets></workbook>
                    """);
            entry(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>
                    """);
            entry(zip, "xl/styles.xml", styles());
            entry(zip, "xl/worksheets/sheet1.xml", sheet("가격 정책", metadata,
                    "할인 방법: common=공통 지원금, choice=선택약정 25%. 금액은 원 단위입니다. 금액을 지우고 업로드하면 미입력으로 변경됩니다. 월별 금액 배열은 1~24개월 순서입니다.",
                    POLICY_HEADERS, POLICY_WIDTHS, policies, 8, 72));
            entry(zip, "xl/worksheets/sheet2.xml", sheet("모델 목록", metadata,
                    "현재 저장된 색상이 있으면 우선 표시하며, 없으면 기본 색상을 표시합니다. 이미지 경로는 함께 배포되는 파일의 위치입니다.",
                    MODEL_HEADERS, MODEL_WIDTHS, models, 5, 48));
        }
        return output.toByteArray();
    }

    private static List<Cell> identifiers(JsonNode product) {
        List<Cell> row = new ArrayList<>();
        row.add(number(product.path("id"), 0));
        row.add(text(value(product.path("brand"))));
        row.add(text(value(product.path("name"))));
        row.add(text(value(product.path("code"))));
        row.add(text(value(product.path("network"))));
        return row;
    }

    private static String savedTime(String stamp) {
        try { return SAVED_TIME.format(Instant.parse(stamp)); }
        catch (RuntimeException error) { return stamp; }
    }

    private static String value(JsonNode node) { return node.isNull() || node.isMissingNode() ? "" : node.asText(); }
    private static Cell text(String value) { return new Cell(value, false, 0); }
    private static Cell amount(JsonNode node) { return number(node, 3); }
    private static Cell number(JsonNode node, int style) {
        return node.isIntegralNumber() ? new Cell(node.asText(), true, style) : new Cell("", false, style);
    }
    private record Cell(String value, boolean numeric, int style) { }

    // 1~2행에 저장 시각과 사용 안내, 3행에 고정 제목을 두고 제목/식별 열을 고정한다.
    private static String sheet(String title, String metadata, String note, String[] headers,
                                double[] widths, List<List<Cell>> rows, int frozenColumns, int rowHeight) {
        String lastColumn = column(headers.length - 1);
        int lastRow = rows.size() + 3;
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        xml.append("<worksheet xmlns=\"").append(NS).append("\"><dimension ref=\"A1:")
                .append(lastColumn).append(lastRow).append("\"/><sheetViews><sheetView workbookViewId=\"0\">")
                .append("<pane xSplit=\"").append(frozenColumns).append("\" ySplit=\"3\" topLeftCell=\"")
                .append(column(frozenColumns)).append("4\" activePane=\"bottomRight\" state=\"frozen\"/>")
                .append("<selection pane=\"bottomRight\" activeCell=\"").append(column(frozenColumns))
                .append("4\" sqref=\"").append(column(frozenColumns)).append("4\"/></sheetView></sheetViews>")
                .append("<sheetFormatPr defaultRowHeight=\"18\"/><cols>");
        for (int col = 0; col < widths.length; col++) xml.append("<col min=\"").append(col + 1)
                .append("\" max=\"").append(col + 1).append("\" width=\"").append(widths[col])
                .append("\" customWidth=\"1\"/>");
        xml.append("</cols><sheetData><row r=\"1\" ht=\"30\" customHeight=\"1\">");
        cell(xml, "A1", new Cell(title + " | " + metadata, false, 2));
        xml.append("</row><row r=\"2\" ht=\"30\" customHeight=\"1\">");
        cell(xml, "A2", new Cell(note, false, 4));
        xml.append("</row><row r=\"3\" ht=\"42\" customHeight=\"1\">");
        for (int col = 0; col < headers.length; col++) cell(xml, column(col) + "3", new Cell(headers[col], false, 1));
        xml.append("</row>");
        for (int index = 0; index < rows.size(); index++) {
            int rowNumber = index + 4;
            xml.append("<row r=\"").append(rowNumber).append("\" ht=\"").append(rowHeight).append("\" customHeight=\"1\">");
            List<Cell> row = rows.get(index);
            for (int col = 0; col < row.size(); col++) cell(xml, column(col) + rowNumber, row.get(col));
            xml.append("</row>");
        }
        xml.append("</sheetData><autoFilter ref=\"A3:").append(lastColumn).append(lastRow)
                .append("\"/><mergeCells count=\"2\"><mergeCell ref=\"A1:").append(lastColumn)
                .append("1\"/><mergeCell ref=\"A2:").append(lastColumn).append("2\"/></mergeCells>")
                .append("<pageMargins left=\"0.3\" right=\"0.3\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/>")
                .append("</worksheet>");
        return xml.toString();
    }

    // 금액은 숫자 셀, 문구는 인라인 문자열로 써서 Excel의 정렬·필터·수정 동작을 유지한다.
    private static void cell(StringBuilder xml, String reference, Cell cell) {
        xml.append("<c r=\"").append(reference).append("\" s=\"").append(cell.style()).append("\"");
        if (cell.value().isEmpty()) { xml.append("/>"); return; }
        if (cell.numeric()) xml.append("><v>").append(cell.value()).append("</v></c>");
        else xml.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(escape(cell.value())).append("</t></is></c>");
    }

    private static String column(int index) {
        StringBuilder name = new StringBuilder();
        for (int value = index + 1; value > 0; value = (value - 1) / 26) name.append((char) ('A' + (value - 1) % 26));
        return name.reverse().toString();
    }

    // 고객 문구를 OOXML에 넣기 전에 XML 제어 문자와 특수 문자를 걸러 문서 손상을 막는다.
    private static String escape(String text) {
        StringBuilder xml = new StringBuilder();
        text.codePoints().forEach(code -> {
            if (!(code == 9 || code == 10 || code == 13 || code >= 0x20 && code <= 0xD7FF
                    || code >= 0xE000 && code <= 0xFFFD || code >= 0x10000 && code <= 0x10FFFF)) return;
            switch (code) {
                case '&' -> xml.append("&amp;");
                case '<' -> xml.append("&lt;");
                case '>' -> xml.append("&gt;");
                case '"' -> xml.append("&quot;");
                case '\'' -> xml.append("&apos;");
                default -> xml.appendCodePoint(code);
            }
        });
        return xml.toString();
    }

    private static void entry(ZipOutputStream zip, String path, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypes() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>
                """;
    }

    private static String styles() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0"/></numFmts>
                  <fonts count="3"><font><sz val="11"/><name val="맑은 고딕"/><family val="2"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="맑은 고딕"/></font><font><b/><color rgb="FF17365D"/><sz val="14"/><name val="맑은 고딕"/></font></fonts>
                  <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF2454EF"/><bgColor indexed="64"/></patternFill></fill></fills>
                  <borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFDCE4EE"/></left><right style="thin"><color rgb="FFDCE4EE"/></right><top style="thin"><color rgb="FFDCE4EE"/></top><bottom style="thin"><color rgb="FFDCE4EE"/></bottom><diagonal/></border></borders>
                  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                  <cellXfs count="5">
                    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
                    <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
                    <xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment vertical="center"/></xf>
                    <xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment horizontal="right" vertical="top"/></xf>
                    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
                  </cellXfs>
                  <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
                </styleSheet>
                """;
    }
}
