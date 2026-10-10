package kr.gyuhwan;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.Map;

/** 공개 통계 수집과 권한이 있는 관리자 집계를 분리한다. 변경 요청은 공통 CSRF 검사도 통과해야 한다. */
@RestController
public class InterestController {
 private final InterestService interest;private final TokenRegistry registry;
 public InterestController(InterestService interest,TokenRegistry registry){this.interest=interest;this.registry=registry;}
 record Event(String event,Integer modelId){}
 @PostMapping("/api/public/interest") ResponseEntity<Void> collect(@RequestBody Event input,HttpServletRequest request,Authentication auth){
  if(!sameOrigin(request))throw new ApiException(403,"같은 사이트의 요청만 처리합니다.");
  // 관리자 로그인 상태의 테스트 클릭은 고객 통계에서 제외한다.
  if(auth!=null&&auth.isAuthenticated()&&!(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken))return ResponseEntity.noContent().build();
  if(input==null)throw new IllegalArgumentException("통계 이벤트를 확인하세요.");
  if(!registry.interestAllowed(request.getRemoteAddr()))throw new ApiException(429,"통계 요청이 많습니다. 잠시 후 시도해 주세요.");
  interest.record(input.event(),input.modelId());return ResponseEntity.noContent().build();
 }
 @GetMapping("/api/admin/interest") Map<String,Object> report(@RequestParam(defaultValue="30") int days,Authentication auth){AdminAccess.require(auth,"STATS_VIEW");return interest.read(days);}
 private static boolean sameOrigin(HttpServletRequest request){
  try{String origin=request.getHeader("Origin");if(origin==null)return false;URI value=URI.create(origin);int port=value.getPort()<0?(value.getScheme().equals("https")?443:80):value.getPort();return value.getScheme().equals(request.getScheme())&&request.getServerName().equalsIgnoreCase(value.getHost())&&port==request.getServerPort()&&value.getUserInfo()==null&&value.getRawPath().isEmpty()&&value.getRawQuery()==null&&value.getRawFragment()==null;}
  catch(RuntimeException error){return false;}
 }
}
