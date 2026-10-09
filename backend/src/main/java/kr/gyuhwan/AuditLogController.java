package kr.gyuhwan;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
// 관리자 전용 수정 기록 목록. 인증·권한은 SecurityConfig가 /api/admin/**에 적용한다.
@RestController
public class AuditLogController {
 private final AuditLogService audit;
 public AuditLogController(AuditLogService audit){this.audit=audit;}
 // 검색어와 작업 종류를 서비스에 전달하며, 결과에는 다음 페이지 존재 여부가 포함된다.
 @GetMapping("/api/admin/audit") Map<String,Object> list(@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String action){return audit.list(offset,limit,q.strip(),action.strip());}
}
