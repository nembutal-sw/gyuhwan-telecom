package kr.gyuhwan;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 화면 노출과 별개로 서버에서 직원의 업무 권한을 검사한다. 대표 관리자 권한은 고정이다. */
public final class AdminAccess {
 public static final List<String> ALL=List.of("PRICE_EDIT","CONTENT_EDIT","CATALOG_EDIT","MEDIA_EDIT","STATS_VIEW","AUDIT_VIEW");
 private AdminAccess(){}
 public static boolean can(String permission){return can(SecurityContextHolder.getContext().getAuthentication(),permission);}
 public static boolean can(Authentication authentication,String permission){
  if(!ALL.contains(permission)||authentication==null||!authentication.isAuthenticated())return false;
  if(authentication.getPrincipal() instanceof AdminPrincipal principal)return principal.isEnabled()&&(principal.owner()||principal.permissions().contains(permission));
  // 별도 인증 공급자나 테스트 Principal도 명시적으로 부여한 권한만 인정한다.
  return authentication.getAuthorities().stream().anyMatch(authority->authority.getAuthority().equals("ROLE_OWNER")||authority.getAuthority().equals("PERM_"+permission));
 }
 public static void require(String permission){if(!can(permission))throw new ApiException(403,"이 작업을 수행할 권한이 없습니다. 대표 관리자에게 권한을 요청하세요.");}
 public static void require(Authentication authentication,String permission){if(!can(authentication,permission))throw new ApiException(403,"이 작업을 수행할 권한이 없습니다. 대표 관리자에게 권한을 요청하세요.");}
}
