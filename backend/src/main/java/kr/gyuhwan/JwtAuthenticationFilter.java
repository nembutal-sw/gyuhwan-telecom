package kr.gyuhwan;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/** 매 요청의 JWT 쿠키를 검증해 SecurityContext를 구성한다. 서버 HTTP 세션은 사용하지 않는다. */
final class JwtAuthenticationFilter extends OncePerRequestFilter {
 private final JwtSessionService tokens;
 private final ObjectMapper mapper;
 private final boolean secure;
 JwtAuthenticationFilter(JwtSessionService tokens,ObjectMapper mapper,boolean secure){this.tokens=tokens;this.mapper=mapper;this.secure=secure;}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  String token=AdminTokenCookies.token(request);
  var existing=SecurityContextHolder.getContext().getAuthentication();
  // 이미 인증된 테스트 요청 등은 덮어쓰지 않는다.
  if(token!=null&&!token.isBlank()&&(existing==null||!existing.isAuthenticated())){
   try{
    AdminPrincipal principal=tokens.authenticate(token);
    // 검증 뒤 비밀번호 해시가 나머지 요청 처리 과정에 남지 않도록 한다.
    principal.eraseCredentials();
    var authentication=UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities());
    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authentication);
   }catch(ApiException error){
    if(error.status==401)AdminTokenCookies.clear(response,secure);
    String path=request.getRequestURI().substring(request.getContextPath().length());
    // 보호된 경로는 검증 실패를 즉시 반환하고, 공개 화면은 익명 사용자로 계속 제공한다.
    if(path.startsWith("/api/admin/")||path.equals("/api/auth/me")||path.equals("/admin_preview")){
     SecurityContextHolder.clearContext();
     response.setStatus(error.status);
     response.setContentType("application/json");
     response.setCharacterEncoding(StandardCharsets.UTF_8.name());
     mapper.writeValue(response.getWriter(),Map.of("error",error.getMessage()));
     return;
    }
   }
  }
  chain.doFilter(request,response);
 }
}
