package kr.gyuhwan;
import java.util.*;import org.springframework.web.bind.annotation.*;
// 기존 상담 자료 조회·삭제용 관리자 API. 새 상담은 카카오톡/전화로 받고,
// 과거 자료는 ImportRunner로 이관될 수 있어 조회와 보존기간 정리 경로를 유지한다.
@RestController @RequestMapping("/api/admin/consultations") public class ConsultationController{private final InquiryService service;public ConsultationController(InquiryService s){service=s;}
 @GetMapping Map<String,Object> list(@RequestParam(defaultValue="0") int offset){service.purge();return Map.of("items",service.consultations(offset));}
 @DeleteMapping("/{id}") Map<String,Object> delete(@PathVariable String id){service.delete(id);return Map.of("deleted",true);}
}
