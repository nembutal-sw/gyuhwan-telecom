package kr.gyuhwan;
import jakarta.servlet.*;import jakarta.servlet.http.*;import java.io.*;import org.springframework.stereotype.Component;import org.springframework.web.filter.OncePerRequestFilter;
/** 요청 본문 크기와 API 캐시 헤더를 제한한다. 로그인 횟수는 CSRF 검사 뒤 AuthController에서 센다. */
@Component @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE) public class ApiLimits extends OncePerRequestFilter{
 protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
  if(req.getRequestURI().startsWith("/api/")){res.setHeader("Cache-Control","no-store");res.setHeader("X-Content-Type-Options","nosniff");}
  if(req.getContentLengthLong()>3_000_000){res.sendError(413);return;}
  // 폼 본문은 Servlet 컨테이너의 max-http-form-post-size 제한에 맡긴다.
  if(req.getContentType()!=null&&req.getContentType().startsWith("application/x-www-form-urlencoded")){chain.doFilter(req,res);return;}
  if(!java.util.Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())){
   byte[] bytes=req.getInputStream().readNBytes(3_000_001);if(bytes.length>3_000_000){res.sendError(413);return;}
   // 크기 확인에 사용한 바이트를 하위 필터와 컨트롤러가 다시 읽도록 요청을 감싼다.
   HttpServletRequestWrapper wrapped=new HttpServletRequestWrapper(req){public ServletInputStream getInputStream(){ByteArrayInputStream in=new ByteArrayInputStream(bytes);return new ServletInputStream(){public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener r){throw new UnsupportedOperationException();}};}public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}};
   chain.doFilter(wrapped,res);return;
  }chain.doFilter(req,res);
 }
}
