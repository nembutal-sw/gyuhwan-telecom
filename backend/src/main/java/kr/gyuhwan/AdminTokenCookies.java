package kr.gyuhwan;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/** JWT를 JavaScript에서 읽을 수 없는 쿠키로 전달하고 로그인·로그아웃에 동일한 속성을 사용한다. */
final class AdminTokenCookies {
 static final String NAME="GYUHWAN_ACCESS";
 static String token(HttpServletRequest request){
  if(request.getCookies()!=null)for(var cookie:request.getCookies())if(cookie.getName().equals(NAME))return cookie.getValue();
  return null;
 }
 // Secure 여부는 접속 구간의 HTTPS 설정과 일치해야 한다. 로컬 HTTP 확인에는 false를 사용한다.
 static void set(HttpServletResponse response,String token,boolean secure,long ttlSeconds){response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(NAME,token).httpOnly(true).secure(secure).sameSite("Strict").path("/").maxAge(ttlSeconds).build().toString());}
 static void clear(HttpServletResponse response,boolean secure){response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(NAME,"").httpOnly(true).secure(secure).sameSite("Strict").path("/").maxAge(0).build().toString());}
 private AdminTokenCookies(){}
}
