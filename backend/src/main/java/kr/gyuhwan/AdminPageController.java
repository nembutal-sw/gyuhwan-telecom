package kr.gyuhwan;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 화면 URL만 정적 HTML로 전달한다. 관리자 데이터 접근 권한은 SecurityConfig에서 별도로 검사한다. */
@Controller
public class AdminPageController {
 @GetMapping("/") String mainPage(){return "forward:/site.html";}
 // 편집용 iframe은 같은 화면을 보여주지만 인증과 프레임 허용 헤더가 적용된다.
 @GetMapping("/admin_preview") String preview(){return "forward:/site.html";}
 @GetMapping("/privacy") String privacy(){return "forward:/privacy.html";}
 @GetMapping("/purchase-guide") String purchaseGuide(){return "forward:/purchase-guide.html";}
 @GetMapping({"/admin_login", "/admin"})
 String adminPage() {
  return "forward:/index.html";
 }
}
