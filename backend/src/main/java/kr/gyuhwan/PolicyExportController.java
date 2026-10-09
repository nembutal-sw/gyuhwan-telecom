package kr.gyuhwan;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
// 관리자가 현재 가격 정책 전체를 엑셀로 내려받는 단일 진입점.
public class PolicyExportController {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final ConfigService configs;
    private final PolicyWorkbookExporter exporter;

    public PolicyExportController(ConfigService configs, PolicyWorkbookExporter exporter) {
        this.configs = configs;
        this.exporter = exporter;
    }

    @GetMapping("/api/admin/policies/export.xlsx")
    public ResponseEntity<byte[]> export() throws Exception {
        String date = LocalDate.now(SEOUL).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String filename = "규환통신_가격정책_" + date + ".xlsx";
        // 두 시트가 같은 저장 시점의 자료를 가리키도록 DB 저장본을 한 번만 읽는다.
        byte[] workbook = exporter.export(configs.read(true));
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(workbook);
    }
}
