package kr.gyuhwan;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** CSRF가 확인된 로그인 요청을 처리하고, 세션 대신 JWT 쿠키와 Redis 등록 정보를 사용한다. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
 private final AuthenticationManager authentication;
 private final JwtSessionService tokens;
 private final TokenRegistry registry;
 private final boolean secure;
 private final long ttlSeconds;
 private final CookieCsrfTokenRepository csrfTokens;
 public AuthController(AuthenticationManager authentication,JwtSessionService tokens,TokenRegistry registry,CookieCsrfTokenRepository csrfTokens,@Value("${app.auth.cookie-secure:${COOKIE_SECURE:false}}") boolean secure,@Value("${app.auth.ttl-seconds:1800}") long ttlSeconds){this.authentication=authentication;this.tokens=tokens;this.registry=registry;this.csrfTokens=csrfTokens;this.secure=secure;this.ttlSeconds=ttlSeconds;}

 // 브라우저는 이 응답의 토큰을 이후 쓰기 요청의 헤더에 넣는다.
 @GetMapping("/csrf") public Map<String,String> csrf(HttpServletRequest request){CsrfToken token=(CsrfToken)request.getAttribute(CsrfToken.class.getName());return Map.of("token",token.getToken(),"headerName",token.getHeaderName());}

 @PostMapping("/login") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void login(@RequestParam(defaultValue="") String username,@RequestParam(defaultValue="") String password,HttpServletRequest request,HttpServletResponse response){
  String address=request.getRemoteAddr();
  // 이 메서드는 Spring Security의 CSRF 검사 뒤에만 실행된다. Redis에서 원자적으로 시도를 예약해 동시 대입 공격을 제한한다.
  try{if(!registry.loginAllowed(address,username)){response.setHeader("Retry-After","600");throw new ApiException(429,"로그인 시도가 많습니다. 잠시 후 다시 시도해 주세요.");}}
  catch(DataAccessException error){throw new ApiException(503,"로그인 서비스를 일시적으로 사용할 수 없습니다.");}
  Authentication authenticated;
  try{authenticated=authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username,password));}
  catch(AuthenticationException error){throw new ApiException(401,"아이디 또는 비밀번호를 확인하세요.");}
  // 정상 로그인은 시도 횟수를 초기화하고 새 인증 쿠키를 발급한다.
  try{registry.clearLoginFailures(address,username);}catch(DataAccessException error){throw new ApiException(503,"로그인 서비스를 일시적으로 사용할 수 없습니다.");}
  AdminPrincipal principal=(AdminPrincipal)authenticated.getPrincipal();
  AdminTokenCookies.set(response,tokens.create(principal),secure,ttlSeconds);
  csrfTokens.saveToken(null,request,response);
 }

 @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void logout(HttpServletRequest request,HttpServletResponse response){
  // Redis의 JTI를 삭제하므로 같은 JWT를 다시 보내도 인증할 수 없다.
  String token=AdminTokenCookies.token(request);
  if(token!=null&&!token.isBlank())tokens.revoke(token);
  AdminTokenCookies.clear(response,secure);
  csrfTokens.saveToken(null,request,response);
  SecurityContextHolder.clearContext();
 }

 @GetMapping("/me") public Map<String,Object> me(Authentication authentication){
  if(authentication.getPrincipal() instanceof AdminPrincipal principal)return Map.of("id",principal.id(),"username",principal.getUsername(),"role","ADMIN","owner",principal.owner(),"revision",principal.revision(),"permissions",principal.permissions(),"enabled",principal.isEnabled(),"mustChangeCredentials",principal.mustChangeCredentials());
  // 계정 API와 무관한 기존 통합 테스트의 모의 Principal도 응답할 수 있게 둔다.
  return Map.of("id","","username",authentication.getName(),"role","ADMIN","owner",authentication.getAuthorities().stream().anyMatch(authority->authority.getAuthority().equals("ROLE_OWNER")),"revision",0,"permissions",AdminAccess.ALL.stream().filter(permission->AdminAccess.can(authentication,permission)).toList(),"enabled",true,"mustChangeCredentials",false);
 }
}
