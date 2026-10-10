package kr.gyuhwan;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** JWT에 계정 정보로는 ID와 revision만 싣고, 요청 시 DB에서 다시 구성하는 관리자 신원 정보다. */
public final class AdminPrincipal implements UserDetails,CredentialsContainer,Serializable {
 private static final long serialVersionUID=1L;
 private final String id;
 private final String username;
 // 직렬화 대상에서 해시를 제외하고 인증이 끝난 뒤 객체의 참조도 지운다.
 private transient String passwordHash;
 private final boolean owner;
 private final long revision;
 private final boolean enabled;
 private final List<String> permissions;

 public AdminPrincipal(String id,String username,String passwordHash,boolean owner,long revision){this(id,username,passwordHash,owner,revision,true,AdminAccess.ALL);}
 public AdminPrincipal(String id,String username,String passwordHash,boolean owner,long revision,boolean enabled,List<String> permissions){this.id=id;this.username=username;this.passwordHash=passwordHash;this.owner=owner;this.revision=revision;this.enabled=enabled;this.permissions=owner?AdminAccess.ALL:List.copyOf(permissions);}
 public String id(){return id;}
 public String accountId(){return id;}
 public boolean owner(){return owner;}
 public long revision(){return revision;}
 public List<String> permissions(){return permissions;}
 @Override public String getUsername(){return username;}
 @Override public String getPassword(){return passwordHash;}
 @Override public Collection<? extends GrantedAuthority> getAuthorities(){java.util.ArrayList<GrantedAuthority> authorities=new java.util.ArrayList<>();authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));if(owner)authorities.add(new SimpleGrantedAuthority("ROLE_OWNER"));permissions.forEach(permission->authorities.add(new SimpleGrantedAuthority("PERM_"+permission)));return List.copyOf(authorities);}
 @Override public boolean isAccountNonExpired(){return true;}
 @Override public boolean isAccountNonLocked(){return true;}
 @Override public boolean isCredentialsNonExpired(){return true;}
 @Override public boolean isEnabled(){return enabled;}
 // 요청 처리 중 해시가 불필요한 곳으로 전달되지 않도록 필터가 검증 직후 호출한다.
 @Override public void eraseCredentials(){passwordHash=null;}
 @Override public String toString(){return "AdminPrincipal[id="+id+", username="+username+", owner="+owner+", revision="+revision+"]";}
}
