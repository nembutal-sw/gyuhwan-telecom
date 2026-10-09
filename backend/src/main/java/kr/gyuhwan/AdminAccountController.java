package kr.gyuhwan;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 계정 API. 계정 변경 후 재로그인이 필요하면 현재 브라우저의 인증 쿠키도 즉시 제거한다. */
@RestController
@RequestMapping("/api/admin/accounts")
public class AdminAccountController {
 private final AdminAccountService accounts;
 private final boolean secure;
 public AdminAccountController(AdminAccountService accounts,@Value("${app.auth.cookie-secure:${COOKIE_SECURE:false}}") boolean secure){this.accounts=accounts;this.secure=secure;}
 record Create(String username,String password){}
 record Update(long revision,String username,String currentPassword,String newPassword){}
 @GetMapping("/me") AdminAccountService.Account me(Authentication authentication){return accounts.me(authentication);}
 @GetMapping AdminAccountService.Account[] list(Authentication authentication){return accounts.list(authentication).toArray(AdminAccountService.Account[]::new);}
 @PostMapping AdminAccountService.Result create(Authentication authentication,@RequestBody Create input){return accounts.create(authentication,input.username(),input.password());}
 @PatchMapping("/me") AdminAccountService.Result self(Authentication authentication,@RequestBody Update input,HttpServletResponse response){return complete(accounts.changeSelf(authentication,input.revision(),input.username(),input.currentPassword(),input.newPassword()),response);}
 @PatchMapping("/{id}") AdminAccountService.Result other(Authentication authentication,@PathVariable String id,@RequestBody Update input,HttpServletResponse response){return complete(accounts.changeOther(authentication,id,input.revision(),input.username(),input.currentPassword(),input.newPassword()),response);}
 // 본인 계정의 아이디나 비밀번호가 바뀌면 기존 JWT의 계정 revision이 더 이상 유효하지 않다.
 private AdminAccountService.Result complete(AdminAccountService.Result result,HttpServletResponse response){
  if(result.requiresLogin()){AdminTokenCookies.clear(response,secure);SecurityContextHolder.clearContext();}
  return result;
 }
}
