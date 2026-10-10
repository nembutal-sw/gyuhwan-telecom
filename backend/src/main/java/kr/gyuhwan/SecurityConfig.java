package kr.gyuhwan;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/** 공개 화면과 관리자 API의 경계, CSRF, 쿠키·프레임 보안 헤더를 정의한다. */
@Configuration
public class SecurityConfig {
 @Bean PasswordEncoder passwords(){return new AdminPasswordEncoder();}
 @Bean CookieCsrfTokenRepository csrfTokens(@Value("${app.auth.cookie-secure:${COOKIE_SECURE:false}}") boolean secure){
  var csrf=new CookieCsrfTokenRepository();
  // CSRF 값은 /api/auth/csrf 응답 본문으로 전달하므로 쿠키는 JavaScript에서 읽을 필요가 없다.
  csrf.setCookieCustomizer(cookie->cookie.httpOnly(true).secure(secure).sameSite("Strict").path("/"));
  return csrf;
 }
 @Bean AuthenticationManager authenticationManager(AdminAccountService accounts,PasswordEncoder passwords){
  var provider=new DaoAuthenticationProvider(accounts);
  provider.setPasswordEncoder(passwords);
  return new ProviderManager(provider);
 }
 @Bean SecurityFilterChain security(HttpSecurity http,JwtSessionService tokens,ObjectMapper mapper,CookieCsrfTokenRepository csrf,@Value("${app.auth.cookie-secure:${COOKIE_SECURE:false}}") boolean secure)throws Exception{
  return http
   .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .securityContext(context->context.requireExplicitSave(true))
   // JWT는 매 요청에서 확인한다. 로그인·로그아웃 시에는 기존 CSRF 토큰을 명시적으로 비운다.
   .csrf(config->config.csrfTokenRepository(csrf).sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy()))
   .authorizeHttpRequests(authorize->authorize
    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
    .requestMatchers("/actuator/health/**","/api/auth/csrf","/api/auth/login","/api/auth/logout","/api/public/**","/","/admin_login","/admin","/index.html","/site.html","/privacy","/privacy.html","/purchase-guide","/purchase-guide.html","/*.css","/*.js","/*.mjs","/*.json","/fonts/**","/core/**","/media/**","/assets/**","/official/**","/products/**","/color-swatches/**","/gyuhwan-logo.png","/gyuhwan-site-qr.png","/gyuhwan-store-map.png","/manufacturer-samsung-user.png","/manufacturer-apple-user.png","/carrier-kt-official.png","/carrier-skt-official.png","/carrier-lgu-transparent.png","/carrier-lgu-supplied.png","/favicon.svg","/favicon.ico").permitAll()
    // 인증된 직원도 맡은 업무의 쓰기 요청만 실행한다. 조회용 가격표·메인 콘텐츠·사진 목록은 공통 열람이다.
    .requestMatchers(request->writeTo(request,"/api/admin/content/images","/api/admin/media")).access((authentication,context)->new AuthorizationDecision(AdminAccess.can(authentication.get(),"MEDIA_EDIT")))
    .requestMatchers(request->writeTo(request,"/api/admin/config","/api/admin/policies","/api/admin/schedules","/api/admin/workspaces/price")).access((authentication,context)->new AuthorizationDecision(AdminAccess.can(authentication.get(),"PRICE_EDIT")))
    .requestMatchers(request->writeTo(request,"/api/admin/content","/api/admin/workspaces/content")).access((authentication,context)->new AuthorizationDecision(AdminAccess.can(authentication.get(),"CONTENT_EDIT")))
    .requestMatchers(request->writeTo(request,"/api/admin/catalog","/api/admin/models")).access((authentication,context)->new AuthorizationDecision(AdminAccess.can(authentication.get(),"CATALOG_EDIT")))
    // 초기 자격 증명을 쓰는 계정은 본인 정보와 자격 증명 변경만 가능하다.
    // 이 두 경로는 아래의 일반 관리자 경로보다 먼저 검사한다.
    .requestMatchers(HttpMethod.GET,"/api/admin/accounts/me").hasRole("ADMIN")
    .requestMatchers(HttpMethod.PATCH,"/api/admin/accounts/me").hasRole("ADMIN")
    .requestMatchers("/api/admin/inquiries","/api/admin/inquiries/**","/api/admin/consultations","/api/admin/consultations/**").access((authentication,context)->new AuthorizationDecision(AdminAccess.owner(authentication.get())))
    .requestMatchers("/api/admin/audit","/api/admin/audit/**").access((authentication,context)->new AuthorizationDecision(AdminAccess.can(authentication.get(),"AUDIT_VIEW")))
    .requestMatchers("/api/admin/interest","/api/admin/stats","/api/admin/stats/**").access((authentication,context)->new AuthorizationDecision(AdminAccess.can(authentication.get(),"STATS_VIEW")))
    // 로그인 화면은 공개하지만 편집용 미리보기와 관리자 데이터는 인증을 요구한다.
    .requestMatchers("/api/admin/**").access((authentication,context)->new AuthorizationDecision(adminAllowed(authentication.get())))
    .requestMatchers("/api/auth/me").hasRole("ADMIN")
    .requestMatchers("/admin_preview").access((authentication,context)->new AuthorizationDecision(adminAllowed(authentication.get())))
    .anyRequest().denyAll())
   .formLogin(login->login.disable())
   .logout(logout->logout.disable())
   .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,error)->response.sendError(401)))
   // 메인 화면은 프레임을 금지하고 인증된 편집용 미리보기만 동일 출처 프레임을 허용한다.
   .headers(headers->headers.frameOptions(frame->frame.disable()).addHeaderWriter((request,response)->{
    Object forwarded=request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
    String uri=forwarded instanceof String original?original:request.getRequestURI();
    boolean preview=uri.substring(request.getContextPath().length()).equals("/admin_preview");
    response.setHeader("X-Frame-Options",preview?"SAMEORIGIN":"DENY");
    response.setHeader("Content-Security-Policy","default-src 'self'; img-src 'self' data: blob: https: http:; style-src 'self' 'unsafe-inline'; font-src 'self'; script-src 'self'; connect-src 'self'; frame-src 'self' https://www.youtube-nocookie.com https://player.vimeo.com; media-src 'self' https:; frame-ancestors "+(preview?"'self'":"'none'")+"; base-uri 'self'; form-action 'self'");
   }))
   .requestCache(cache->cache.disable())
   .addFilterAfter(new JwtAuthenticationFilter(tokens,mapper,secure),SecurityContextHolderFilter.class)
   .build();
 }
 private static boolean writeTo(HttpServletRequest request,String... prefixes){
  if(request.getMethod().equals("GET")||request.getMethod().equals("HEAD")||request.getMethod().equals("OPTIONS"))return false;
  // MVC와 같은 PathPattern parser를 사용해 %63onfig 같은 인코딩 경로도 검사한다.
  for(String prefix:prefixes){
   if(PathPatternRequestMatcher.pathPattern(prefix).matches(request)||PathPatternRequestMatcher.pathPattern(prefix+"/**").matches(request))return true;
  }
  return false;
 }
 private static boolean adminAllowed(Authentication authentication){
  return authentication!=null&&authentication.isAuthenticated()
   &&authentication.getAuthorities().stream().anyMatch(authority->authority.getAuthority().equals("ROLE_ADMIN"))
   &&!(authentication.getPrincipal() instanceof AdminPrincipal principal&&principal.mustChangeCredentials());
 }
}
