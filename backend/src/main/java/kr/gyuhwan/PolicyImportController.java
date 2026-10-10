package kr.gyuhwan;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/policies")
// 엑셀 가져오기는 미리보기와 실제 저장을 분리해 운영자가 변경 내역을 확인할 수 있게 한다.
public class PolicyImportController {
 private final PolicyImportService service;

 public PolicyImportController(PolicyImportService service){this.service=service;}

 // 이 경로는 검증·차이 계산만 하며 DB를 수정하지 않는다.
 @PostMapping("/import-excel/preview")
 PolicyImportService.Preview preview(@RequestBody JsonNode input){AdminAccess.require("PRICE_EDIT");return service.previewExcel(revision(input),text(input,"fileName"),text(input,"base64"));}

 // 동일 파일도 적용 시점의 revision을 다시 검사한 뒤 원자적으로 저장한다.
 @PostMapping("/import-excel")
 PolicyImportService.Result save(@RequestBody JsonNode input){AdminAccess.require("PRICE_EDIT");return service.importExcel(revision(input),text(input,"fileName"),text(input,"base64"));}

 // 허용된 요청 필드만 받고 정수 revision을 강제한다.
 private long revision(JsonNode input){
  if(input==null||!input.isObject())throw new IllegalArgumentException("정책 가져오기 형식을 확인하세요.");
  input.fieldNames().forEachRemaining(field->{if(!Set.of("revision","fileName","base64").contains(field))throw new IllegalArgumentException("알 수 없는 가져오기 항목");});
  JsonNode revision=input.path("revision");
  if(!revision.isIntegralNumber()||!revision.canConvertToLong()||revision.asLong()<1)throw new IllegalArgumentException("저장 버전 오류");
  return revision.asLong();
 }
 private String text(JsonNode input,String field){if(!input.path(field).isTextual())throw new IllegalArgumentException("엑셀 가져오기 파일 정보를 확인하세요.");return input.path(field).asText();}
}
