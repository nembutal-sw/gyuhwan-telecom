package kr.gyuhwan;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
// 메인 페이지 필드와 실제 공개 JSON 자료를 같은 저장본에서 제공한다.
public class SiteContentController {
 private final SiteContentService service;
 public SiteContentController(SiteContentService service){this.service=service;}
 // 공개/관리자 화면이 같은 필드 구조를 읽고, 수정 권한은 보안 경로 규칙으로 구분한다.
 @GetMapping({"/api/public/content","/api/admin/content"}) Map<String,Object> read(){return service.read();}
 @GetMapping("/api/public/content/data/{resource}") JsonNode resource(@PathVariable String resource){return service.resource(resource);}
 // 부분 수정(values)도 revision을 요구해 동시 편집에서 덮어쓰기를 막는다.
 record Save(long revision,JsonNode values){}
 @PutMapping("/api/admin/content") Map<String,Object> save(@RequestBody Save input){AdminAccess.require("CONTENT_EDIT");return service.save(input.revision(),input.values());}
 @GetMapping("/api/admin/content/history") List<Map<String,Object>> history(){return service.history();}
 record Restore(long revision,long targetRevision){}
 @PostMapping("/api/admin/content/restore") Map<String,Object> restore(@RequestBody Restore input)throws Exception{AdminAccess.require("CONTENT_EDIT");return service.restore(input.revision(),input.targetRevision());}
}
