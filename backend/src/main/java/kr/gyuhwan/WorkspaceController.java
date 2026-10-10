package kr.gyuhwan;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** URL의 계정 ID를 받지 않고 현재 인증 계정의 초안에만 접근한다. */
@RestController
public class WorkspaceController {
 private final WorkspaceService service;
 public WorkspaceController(WorkspaceService service){this.service=service;}
 @GetMapping("/api/admin/workspaces/{kind}") Map<String,Object> read(@PathVariable String kind){return service.read(kind);}
 record Save(long revision,long baseRevision,JsonNode payload){}
 @PutMapping("/api/admin/workspaces/{kind}") Map<String,Object> save(@PathVariable String kind,@RequestBody Save input){return service.save(kind,input.revision(),input.baseRevision(),input.payload());}
 @DeleteMapping("/api/admin/workspaces/{kind}") void delete(@PathVariable String kind,@RequestParam long revision){service.delete(kind,revision);}
}
