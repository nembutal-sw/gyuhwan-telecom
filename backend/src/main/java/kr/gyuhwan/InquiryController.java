package kr.gyuhwan;
import java.util.*;import org.springframework.web.bind.annotation.*;
// 과거 문의 자료 관리용 API. 신규 공개 문의 접수 경로는 없지만 이전 자료의 답변·삭제·보존기간 처리를 지원한다.
@RestController @RequestMapping("/api/admin/inquiries") public class InquiryController{private final InquiryService service;public InquiryController(InquiryService s){service=s;}
 @GetMapping Map<String,Object> list(@RequestParam(defaultValue="0") int offset){service.purge();return Map.of("items",service.list(offset));}
 record Reply(long revision,String reply){} @PatchMapping("/{id}") Map<String,Object> reply(@PathVariable String id,@RequestBody Reply r){service.purge();return service.reply(id,r.revision(),r.reply());}
 @DeleteMapping("/{id}") Map<String,Object> delete(@PathVariable String id){service.delete(id);return Map.of("deleted",true);}
 @PostMapping("/purge") Map<String,Object> purge(){return Map.of("deletedCount",service.purge());}
}
