package kr.gyuhwan;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 계정을 관계형 DB에서 관리한다. 계정 변경과 감사 기록은 같은 트랜잭션으로 확정한다. */
@Service
public class AdminAccountService implements UserDetailsService {
 private final JdbcTemplate db;
 private final PasswordEncoder passwords;
 private final AuditLogService audit;
 private final String bootstrapUsername;
 private String bootstrapPassword;

 public AdminAccountService(JdbcTemplate db,PasswordEncoder passwords,AuditLogService audit,@Value("${app.admin-user}") String username,@Value("${app.admin-password}") String password){this.db=db;this.passwords=passwords;this.audit=audit;this.bootstrapUsername=username;this.bootstrapPassword=password;}
 // 초기 계정 비밀번호는 최초 생성에만 사용하고 이 서비스의 참조를 해제한다.
 @PostConstruct void seed(){bootstrap(bootstrapUsername,bootstrapPassword);bootstrapPassword=null;}

 /** 계정이 이미 있으면 환경 변수의 초기 자격 증명으로 기존 계정을 덮어쓰지 않는다. */
 public void bootstrap(String username,String password){
  if(db.queryForObject("SELECT COUNT(*) FROM admin_accounts",Integer.class)>0)return;
  validateUsername(username);validatePassword(password);
  String stamp=Times.stamp(Instant.now());
  try{db.update("INSERT INTO admin_accounts(id,username,username_key,password_hash,is_owner,owner_slot,revision,created_at,updated_at) VALUES(?,?,?,?,TRUE,1,1,?,?)",UUID.randomUUID().toString(),username,key(username),passwords.encode(password),stamp,stamp);}
  catch(DuplicateKeyException e){if(db.queryForObject("SELECT COUNT(*) FROM admin_accounts",Integer.class)==0)throw e;}
 }

 public record Account(String id,String username,boolean owner,long revision,boolean enabled,List<String> permissions){}
 public record Result(String id,String username,boolean owner,long revision,boolean requiresLogin,boolean enabled,List<String> permissions){}

 // username_key를 통해 아이디 대소문자를 구분하지 않되 입력 형식은 먼저 제한한다.
 @Override public AdminPrincipal loadUserByUsername(String username)throws UsernameNotFoundException{
  if(username==null||!username.matches("[A-Za-z0-9._-]{3,50}"))throw new UsernameNotFoundException("관리자 계정을 찾을 수 없습니다.");
  List<AdminPrincipal> found=db.query("SELECT id,username,password_hash,is_owner,revision,enabled,permissions FROM admin_accounts WHERE username_key=?",(rs,n)->new AdminPrincipal(rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4),rs.getLong(5),rs.getBoolean(6),storedPermissions(rs.getString(7))),key(username));
  if(found.isEmpty())throw new UsernameNotFoundException("관리자 계정을 찾을 수 없습니다.");
  return found.get(0);
 }

 public AdminPrincipal principalById(String id){
  List<AdminPrincipal> found=db.query("SELECT id,username,password_hash,is_owner,revision,enabled,permissions FROM admin_accounts WHERE id=?",(rs,n)->new AdminPrincipal(rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4),rs.getLong(5),rs.getBoolean(6),storedPermissions(rs.getString(7))),id);
  if(found.isEmpty())throw new UsernameNotFoundException("관리자 계정을 찾을 수 없습니다.");
  return found.get(0);
 }

 // JWT 발급 직전 계정 revision을 확인해 이미 변경된 계정으로 새 토큰을 만들지 않는다.
 public boolean current(AdminPrincipal principal){return db.queryForObject("SELECT COUNT(*) FROM admin_accounts WHERE id=? AND revision=? AND enabled=TRUE",Integer.class,principal.id(),principal.revision())==1;}
 public Account me(Authentication authentication){return account(actor(authentication));}
 public List<Account> list(Authentication authentication){
  owner(authentication);
  return db.query("SELECT id,username,is_owner,revision,enabled,permissions FROM admin_accounts ORDER BY is_owner DESC,username_key",(rs,n)->new Account(rs.getString(1),rs.getString(2),rs.getBoolean(3),rs.getLong(4),rs.getBoolean(5),rs.getBoolean(3)?AdminAccess.ALL:storedPermissions(rs.getString(6))));
 }

 /** 대표 관리자만 추가할 수 있다. 감사 기록 저장에 실패하면 계정 생성도 롤백한다. */
 @Transactional public Result create(Authentication authentication,String username,String password){
  return create(authentication,username,password,null,null,null);
 }
 @Transactional public Result create(Authentication authentication,String username,String password,List<String> requestedPermissions,Boolean requestedEnabled,String currentPassword){
  AdminPrincipal actor=owner(authentication);validateUsername(username);validatePassword(password);
  if(requestedPermissions!=null||requestedEnabled!=null)verifyCurrent(actor,currentPassword);
  List<String> permissions=requestedPermissions==null?AdminAccess.ALL:validatePermissions(requestedPermissions);
  boolean enabled=requestedEnabled==null||requestedEnabled;
  String id=UUID.randomUUID().toString(),stamp=Times.stamp(Instant.now());
  try{db.update("INSERT INTO admin_accounts(id,username,username_key,password_hash,is_owner,owner_slot,revision,created_at,updated_at,enabled,permissions) VALUES(?,?,?,?,FALSE,NULL,1,?,?,?,?)",id,username,key(username),passwords.encode(password),stamp,stamp,enabled,String.join(",",permissions));}
  catch(DuplicateKeyException e){throw duplicate();}
  audit.record("ACCOUNT_CREATE",target(id,username),List.of(change("username","아이디",null,username),change("password","비밀번호","미설정","설정됨"),change("owner","대표 관리자",null,false),change("permissions","업무 권한",null,permissionLabels(permissions)),change("enabled","계정 사용",null,enabled?"사용 중":"사용 중지")));
  return new Result(id,username,false,1,false,enabled,permissions);
 }

 @Transactional public Result changeSelf(Authentication authentication,long revision,String username,String currentPassword,String newPassword){
  AdminPrincipal actor=actor(authentication);
  return change(actor,actor,revision,username,currentPassword,newPassword,null,null);
 }
 @Transactional public Result changeSelf(Authentication authentication,long revision,String username,String currentPassword,String newPassword,List<String> permissions,Boolean enabled){
  AdminPrincipal actor=actor(authentication);return change(actor,actor,revision,username,currentPassword,newPassword,permissions,enabled);
 }

 @Transactional public Result changeOther(Authentication authentication,String id,long revision,String username,String currentPassword,String newPassword){
  AdminPrincipal actor=owner(authentication);
  AdminPrincipal target;
  try{target=principalById(id);}catch(UsernameNotFoundException e){throw new ApiException(404,"관리자 계정을 찾을 수 없습니다.");}
  return change(actor,target,revision,username,currentPassword,newPassword,null,null);
 }
 @Transactional public Result changeOther(Authentication authentication,String id,long revision,String username,String currentPassword,String newPassword,List<String> permissions,Boolean enabled){
  AdminPrincipal actor=owner(authentication);AdminPrincipal target;
  try{target=principalById(id);}catch(UsernameNotFoundException e){throw new ApiException(404,"관리자 계정을 찾을 수 없습니다.");}
  return change(actor,target,revision,username,currentPassword,newPassword,permissions,enabled);
 }

 // 입력 revision과 DB 조건을 함께 검사해 다른 관리자의 동시 변경을 덮어쓰지 않는다.
 // 비밀번호 원문과 해시는 감사 기록에 남기지 않는다.
 private Result change(AdminPrincipal actor,AdminPrincipal target,long revision,String username,String currentPassword,String newPassword,List<String> requestedPermissions,Boolean requestedEnabled){
  if(revision<1)throw new IllegalArgumentException("계정 버전 오류");
  if(target.revision()!=revision)throw conflict();
  verifyCurrent(actor,currentPassword);validateUsername(username);
  if((requestedPermissions!=null||requestedEnabled!=null)&&!actor.owner())throw new ApiException(403,"대표 관리자만 업무 권한과 계정 사용 상태를 변경할 수 있습니다.");
  List<String> permissions=requestedPermissions==null?target.permissions():validatePermissions(requestedPermissions);
  boolean enabled=requestedEnabled==null?target.isEnabled():requestedEnabled;
  if(target.owner()&&(!enabled||!permissions.equals(AdminAccess.ALL)))throw new ApiException(403,"대표 관리자 계정은 사용 중지하거나 권한을 변경할 수 없습니다.");
  boolean permissionChanged=!permissions.equals(target.permissions()),enabledChanged=enabled!=target.isEnabled();
  boolean hasPassword=newPassword!=null&&!newPassword.isEmpty();
  if(hasPassword)validatePassword(newPassword);
  boolean renamed=!target.getUsername().equals(username),passwordChanged=hasPassword&&!passwords.matches(newPassword,target.getPassword());
  if(!renamed&&!passwordChanged&&!permissionChanged&&!enabledChanged)return new Result(target.id(),target.getUsername(),target.owner(),target.revision(),false,target.isEnabled(),target.permissions());
  String hash=passwordChanged?passwords.encode(newPassword):target.getPassword();
  try{
   if(db.update("UPDATE admin_accounts SET username=?,username_key=?,password_hash=?,permissions=?,enabled=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",username,key(username),hash,String.join(",",permissions),enabled,Times.stamp(Instant.now()),target.id(),revision)!=1)throw conflict();
  }catch(DuplicateKeyException e){throw duplicate();}
  List<Map<String,Object>> changes=new ArrayList<>();
  if(renamed)changes.add(change("username","아이디",target.getUsername(),username));
  if(passwordChanged)changes.add(change("password","비밀번호","설정됨","변경됨"));
  if(permissionChanged)changes.add(change("permissions","업무 권한",permissionLabels(target.permissions()),permissionLabels(permissions)));
  if(enabledChanged)changes.add(change("enabled","계정 사용",target.isEnabled()?"사용 중":"사용 중지",enabled?"사용 중":"사용 중지"));
  audit.record("ACCOUNT_UPDATE",target(target.id(),username),changes);
  return new Result(target.id(),username,target.owner(),revision+1,target.id().equals(actor.id()),enabled,permissions);
 }

 // 요청의 Principal만 신뢰하지 않고 DB의 현재 revision을 다시 읽어 변경된 계정의 요청을 거부한다.
 private AdminPrincipal actor(Authentication authentication){
  if(authentication==null||!authentication.isAuthenticated()||!(authentication.getPrincipal() instanceof AdminPrincipal principal))throw new ApiException(401,"다시 로그인하세요.");
  AdminPrincipal stored;
  try{stored=principalById(principal.id());}catch(UsernameNotFoundException e){throw new ApiException(401,"다시 로그인하세요.");}
  if(!stored.isEnabled()||stored.revision()!=principal.revision())throw new ApiException(401,"계정 정보가 변경되었습니다. 다시 로그인하세요.");
  return stored;
 }
 private AdminPrincipal owner(Authentication authentication){AdminPrincipal actor=actor(authentication);if(!actor.owner())throw new ApiException(403,"대표 관리자만 다른 관리자 계정을 관리할 수 있습니다.");return actor;}
 private void verifyCurrent(AdminPrincipal actor,String current){if(current==null||current.length()>128||!passwords.matches(current,actor.getPassword()))throw new IllegalArgumentException("현재 비밀번호를 확인하세요.");}
 private static Account account(AdminPrincipal principal){return new Account(principal.id(),principal.getUsername(),principal.owner(),principal.revision(),principal.isEnabled(),principal.permissions());}
 // 빈 목록은 조회 전용 계정이다. 저장 순서를 고정해 같은 권한 배열의 순서 차이는 변경으로 보지 않는다.
 private static List<String> validatePermissions(List<String> permissions){if(permissions.size()>AdminAccess.ALL.size()||permissions.stream().anyMatch(permission->permission==null||!AdminAccess.ALL.contains(permission))||permissions.stream().distinct().count()!=permissions.size())throw new IllegalArgumentException("알 수 없거나 중복된 업무 권한입니다.");return AdminAccess.ALL.stream().filter(permissions::contains).toList();}
 private static List<String> storedPermissions(String permissions){if(permissions==null||permissions.isEmpty())return List.of();List<String> values=Arrays.asList(permissions.split(","));return AdminAccess.ALL.stream().filter(values::contains).toList();}
 private static List<String> permissionLabels(List<String> permissions){return permissions.stream().map(permission->switch(permission){case "PRICE_EDIT"->"가격표 수정";case "CONTENT_EDIT"->"메인페이지 수정";case "CATALOG_EDIT"->"모델 등록·판매 상태";case "MEDIA_EDIT"->"사진 보관함 수정";case "STATS_VIEW"->"관심 통계 조회";case "AUDIT_VIEW"->"수정 기록 조회";default->permission;}).toList();}
 private static void validateUsername(String username){if(username==null||!username.matches("[A-Za-z0-9._-]{3,50}"))throw new IllegalArgumentException("아이디는 영문·숫자·점·밑줄·하이픈 3~50자로 입력하세요.");}
 private static void validatePassword(String password){if(password==null||password.length()<16||password.length()>128||password.isBlank())throw new IllegalArgumentException("비밀번호는 16~128자로 입력하세요.");}
 private static String key(String username){return username.toLowerCase(Locale.ROOT);}
 private static String target(String id,String username){return "관리자 계정 #"+id+" · "+username;}
 private static Map<String,Object> change(String field,String label,Object before,Object after){Map<String,Object> change=new LinkedHashMap<>();change.put("field",field);change.put("label",label);change.put("before",before);change.put("after",after);return change;}
 private static ApiException duplicate(){return new ApiException(409,"이미 사용 중인 관리자 아이디입니다.");}
 private static ApiException conflict(){return new ApiException(409,"다른 화면에서 계정 정보를 먼저 수정했습니다. 최신 계정 정보를 확인하세요.");}
}
