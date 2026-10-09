package kr.gyuhwan;
import com.fasterxml.jackson.databind.JsonNode;import java.util.*;import org.springframework.web.bind.annotation.*;
// 가격 정책 API. 공개 조회와 관리자 변경을 같은 저장본에 연결한다.
@RestController public class ConfigController{
 private final ConfigService service;public ConfigController(ConfigService s){service=s;}
 // 공개 가격표는 서비스의 짧은 캐시를 쓰며 관리자 조회는 저장소를 직접 읽는다.
 @GetMapping("/api/public/config") Map<String,Object> readPublic(){return service.read(false);}
 @GetMapping("/api/admin/config") Map<String,Object> read(){return service.read(true);}
 // revision은 낙관적 잠금용이다. 다른 관리자가 먼저 저장했으면 서비스가 409를 반환한다.
 record Save(long revision,JsonNode config){} @PutMapping("/api/admin/config") Map<String,Object> save(@RequestBody Save in){return service.save(in.revision(),in.config());}
 // 과거 저장본을 현재 저장본으로 다시 적용할 때도 새 revision과 감사 기록이 생긴다.
 @GetMapping("/api/admin/config/history") List<Map<String,Object>> history(){return service.history();}
 record Restore(long revision,long targetRevision){} @PostMapping("/api/admin/config/restore") Map<String,Object> restore(@RequestBody Restore in)throws Exception{return service.restore(in.revision(),in.targetRevision());}
}
