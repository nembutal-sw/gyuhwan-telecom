package kr.gyuhwan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.test.context.ActiveProfiles("test") @SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:policy-export-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.admin-password=test-only-admin-password-long", "app.retention-cron=-"
})
@AutoConfigureMockMvc
/** 관리자 가격 정책 엑셀 내보내기의 권한, 셀 형식, 최신성 및 읽기 전용 동작을 검증한다. */
class PolicyExportApiTest {
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate db;
    @Autowired ConfigService configs;

    @BeforeEach
    void reset() throws Exception {
        db.update("DELETE FROM config_history");
        db.update("DELETE FROM quote_config");
        configs.seed();
    }

    // 내보내기 파일은 관리자 인증이 있어야 받을 수 있다.
    @Test
    void exportRequiresAdministratorAuthentication() throws Exception {
        mvc.perform(get("/api/admin/policies/export.xlsx")).andExpect(status().isUnauthorized());
    }

    // 모든 정책 조건과 모델 목록, 숫자·빈값·문자 셀을 확인하고 수식 주입과 저장 부작용을 막는다.
    @Test
    @WithMockUser(roles = "ADMIN")
    void workbookContainsEveryConditionAndPreservesTypedValuesAndSafeText() throws Exception {
        ObjectNode config = fixture();
        configs.save(1, config);
        Map<String, Object> before = configs.read(true);
        int historyBefore = configs.history().size();
        var response = mvc.perform(get("/api/admin/policies/export.xlsx"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", response.getContentType());
        assertTrue(response.getHeader("Content-Disposition").startsWith("attachment;"));
        assertTrue(response.getHeader("Content-Disposition").contains("filename*=UTF-8''"));
        assertTrue(response.getHeader("Content-Disposition").contains(LocalDate.now(ZoneId.of("Asia/Seoul")).toString()));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        Map<String, Document> workbook = documents(response.getContentAsByteArray());
        assertTrue(workbook.containsKey("[Content_Types].xml"));
        Document book = workbook.get("xl/workbook.xml");
        NodeList sheets = book.getElementsByTagNameNS(NS, "sheet");
        assertEquals(2, sheets.getLength());
        assertEquals("가격 정책", ((Element) sheets.item(0)).getAttribute("name"));
        assertEquals("모델 목록", ((Element) sheets.item(1)).getAttribute("name"));
        Document policies = workbook.get("xl/worksheets/sheet1.xml");
        Document models = workbook.get("xl/worksheets/sheet2.xml");
        assertEquals(867, policies.getElementsByTagNameNS(NS, "row").getLength());
        assertEquals(51, models.getElementsByTagNameNS(NS, "row").getLength());
        assertEquals("A3:X867", ((Element) policies.getElementsByTagNameNS(NS, "autoFilter").item(0)).getAttribute("ref"));
        assertEquals("8", ((Element) policies.getElementsByTagNameNS(NS, "pane").item(0)).getAttribute("xSplit"));
        assertEquals("3", ((Element) policies.getElementsByTagNameNS(NS, "pane").item(0)).getAttribute("ySplit"));
        Set<String> keys = new HashSet<>();
        Map<String, Integer> rowsPerId = new HashMap<>();
        int registered = 0;
        NodeList rows = policies.getElementsByTagNameNS(NS, "row");
        for (int index = 3; index < rows.getLength(); index++) {
            Map<String, Element> cells = rowCells((Element) rows.item(index));
            String id = value(cells.get("A"));
            String key = id + "|" + value(cells.get("F")) + "|" + value(cells.get("G")) + "|" + value(cells.get("H"));
            assertTrue(keys.add(key), "Duplicate export condition " + key);
            assertTrue(Set.of("common", "choice").contains(value(cells.get("H"))));
            rowsPerId.merge(id, 1, Integer::sum);
            if (value(cells.get("I")).equals("등록")) registered++;
        }
        assertEquals(864, keys.size());
        assertEquals(48, rowsPerId.size());
        rowsPerId.forEach((id, count) -> assertEquals(18, count, id));
        assertEquals(1, registered);
        Map<String, Element> first = rowCells((Element) rows.item(3));
        assertEquals("1254000", value(first.get("J")));
        assertEquals("3", first.get("J").getAttribute("s"));
        assertEquals("", first.get("J").getAttribute("t"), "Amounts must be numeric cells");
        assertEquals(0, first.get("K").getElementsByTagNameNS(NS, "v").getLength(), "Null must remain blank");
        assertEquals("0", value(first.get("L")), "A saved zero must remain a numeric zero");
        assertEquals("", first.get("L").getAttribute("t"));
        assertEquals("예", value(first.get("M")));
        assertEquals("=SUM(1,2)", value(first.get("O")));
        assertEquals("inlineStr", first.get("O").getAttribute("t"));
        assertEquals(0, policies.getElementsByTagNameNS(NS, "f").getLength(), "Text must never become a formula");
        assertEquals(config.path("offers").path("0|SKT|번호이동|common").path("addonSchedule").toString(), value(first.get("T")));
        assertEquals("안내 <&> \" ' 🙂", value(first.get("X")), "Unsafe XML controls must be filtered without losing text");
        assertTrue(value(cell(policies, "A1")).contains("저장 버전: 2"));
        assertTrue(value(cell(policies, "A1")).contains("저장 시각 (KST):"));
        assertEquals("매장 화이트 / 매장 블랙", value(cell(models, "F4")));
        assertEquals("현재 저장값", value(cell(models, "G4")));
        assertEquals("버건디 / 글레이셔 / 실버 / 블랙", value(cell(models, "F42")));
        assertEquals("기본 색상", value(cell(models, "G42")));
        Document styles = workbook.get("xl/styles.xml");
        assertEquals("#,##0", ((Element) styles.getElementsByTagNameNS(NS, "numFmt").item(0)).getAttribute("formatCode"));
        assertEquals(before, configs.read(true), "Export must not change the saved config or revision");
        assertEquals(historyBefore, configs.history().size(), "Export must not add history");
    }

    // 매 다운로드는 현재 저장된 정책으로 생성하며, 다운로드 자체는 버전과 이력을 바꾸지 않는다.
    @Test
    @WithMockUser(roles = "ADMIN")
    void eachDownloadUsesTheCurrentSavedPolicy() throws Exception {
        ObjectNode config = fixture();
        configs.save(1, config);
        Document original = documents(download()).get("xl/worksheets/sheet1.xml");
        assertEquals("1254000", value(cell(original, "J4")));
        ((ObjectNode) config.path("offers").path("0|SKT|번호이동|common")).put("retail", 999000);
        configs.save(2, config);
        Map<String, Object> before = configs.read(true);
        Document changed = documents(download()).get("xl/worksheets/sheet1.xml");
        assertEquals("999000", value(cell(changed, "J4")));
        assertTrue(value(cell(changed, "A1")).contains("저장 버전: 3"));
        assertEquals(before, configs.read(true));
        assertEquals(2, configs.history().size());
    }

    private ObjectNode fixture() {
        ObjectNode config = mapper.createObjectNode();
        ObjectNode offer = config.putObject("offers").putObject("0|SKT|번호이동|common");
        offer.put("retail", 1254000).putNull("common").put("support", 0).put("supportInquiry", true);
        offer.put("planName", "=SUM(1,2)").put("planFee", 109000).putNull("discountBase");
        offer.put("addonFee", 0).put("addonMonths", 24).put("terms", "안내 <&> \" ' \u0001🙂");
        var schedule = offer.putArray("addonSchedule");
        for (int month = 0; month < 24; month++) schedule.add(month);
        config.putObject("colors").putArray("0").add("매장 화이트").add("매장 블랙");
        return config;
    }

    private byte[] download() throws Exception {
        return mvc.perform(get("/api/admin/policies/export.xlsx"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private static Map<String, Document> documents(byte[] bytes) throws Exception {
        assertEquals('P', bytes[0]);
        assertEquals('K', bytes[1]);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Map<String, Document> documents = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                documents.put(entry.getName(), factory.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes())));
            }
        }
        assertEquals(7, documents.size(), "All required OOXML ZIP parts must be present");
        return documents;
    }

    private static Map<String, Element> rowCells(Element row) {
        Map<String, Element> cells = new HashMap<>();
        NodeList nodes = row.getElementsByTagNameNS(NS, "c");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element cell = (Element) nodes.item(index);
            cells.put(cell.getAttribute("r").replaceAll("[0-9]", ""), cell);
        }
        return cells;
    }

    private static Element cell(Document document, String reference) {
        NodeList cells = document.getElementsByTagNameNS(NS, "c");
        for (int index = 0; index < cells.getLength(); index++) {
            Element cell = (Element) cells.item(index);
            if (cell.getAttribute("r").equals(reference)) return cell;
        }
        throw new AssertionError("Missing workbook cell " + reference);
    }

    private static String value(Element cell) {
        NodeList numbers = cell.getElementsByTagNameNS(NS, "v");
        return numbers.getLength() > 0 ? numbers.item(0).getTextContent() : cell.getTextContent();
    }
}
