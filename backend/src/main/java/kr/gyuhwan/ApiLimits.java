package kr.gyuhwan;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** API 응답 캐시와 요청 본문 크기를 제한한다. 본문은 인증·CSRF 검사 전에 읽지 않는다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiLimits extends OncePerRequestFilter {
 static final int MAX_BODY_BYTES=3_000_000;

 static final class RequestTooLargeException extends IOException {
  private static final long serialVersionUID=1L;
  RequestTooLargeException(){super("요청 본문은 3 MB를 넘을 수 없습니다.");}
 }

 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
  if(req.getRequestURI().startsWith(req.getContextPath()+"/api/")){
   res.setHeader("Cache-Control","no-store");
   res.setHeader("X-Content-Type-Options","nosniff");
  }
  if(req.getContentLengthLong()>MAX_BODY_BYTES){res.sendError(413);return;}
  // 폼은 컨테이너의 max-http-form-post-size 제한을 사용한다. 파라미터를 먼저 읽으면
  // 로그인과 CSRF 검사가 기대하는 Servlet 파라미터 동작이 바뀔 수 있다.
  if(Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())||req.getContentType()!=null&&req.getContentType().startsWith("application/x-www-form-urlencoded")){
   chain.doFilter(req,res);return;
  }
  ServletInputStream source=req.getInputStream();
  ServletInputStream limited=new ServletInputStream(){
   private long seen;
   private void count(int n)throws IOException{if(n>0&&((seen+=n)>MAX_BODY_BYTES))throw new RequestTooLargeException();}
   @Override public int read()throws IOException{int value=source.read();if(value!=-1)count(1);return value;}
   @Override public int read(byte[] bytes,int offset,int length)throws IOException{
    if(length==0)return 0;
    // 한 추가 바이트를 허용해 한계 초과를 감지하되 소비량을 제한한다.
    int remaining=(int)Math.min((long)length,MAX_BODY_BYTES+1L-seen);
    if(remaining<=0)throw new RequestTooLargeException();
    int n=source.read(bytes,offset,remaining);count(n);return n;
   }
   @Override public boolean isFinished(){return source.isFinished();}
   @Override public boolean isReady(){return source.isReady();}
   @Override public void setReadListener(ReadListener listener){source.setReadListener(listener);}
  };
  HttpServletRequest wrapped=new HttpServletRequestWrapper(req){
   @Override public ServletInputStream getInputStream(){return limited;}
   @Override public BufferedReader getReader()throws IOException{
    String encoding=getCharacterEncoding();
    return new BufferedReader(new InputStreamReader(limited,encoding==null?StandardCharsets.UTF_8:Charset.forName(encoding)));
   }
  };
  try{chain.doFilter(wrapped,res);}catch(RequestTooLargeException tooLarge){if(!res.isCommitted())res.sendError(413);}
 }
}
