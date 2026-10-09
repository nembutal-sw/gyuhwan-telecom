package kr.gyuhwan;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** 서명된 JWT와 Redis JTI 등록, DB 계정 revision을 함께 확인해 관리자 로그인을 유지한다. */
@Service
public class JwtSessionService {
 private static final String ISSUER="gyuhwan-admin";
 private final JwtEncoder encoder;
 private final JwtDecoder decoder;
 private final TokenRegistry registry;
 private final AdminAccountService accounts;
 private final Duration ttl;
 public JwtSessionService(TokenRegistry registry,AdminAccountService accounts,@Value("${app.auth.jwt-secret}") String secret,@Value("${app.auth.ttl-seconds:1800}") long seconds){
  byte[] key;try{key=Base64.getDecoder().decode(secret);}catch(IllegalArgumentException e){throw new IllegalStateException("JWT_SECRET은 Base64 문자열이어야 합니다.");}
  if(key.length<32)throw new IllegalStateException("JWT_SECRET은 32바이트 이상의 무작위 키를 Base64로 인코딩해 설정하세요.");
  if(seconds<60||seconds>86400)throw new IllegalStateException("JWT 유효 기간은 60~86,400초로 설정하세요.");
  var signingKey=new SecretKeySpec(key,"HmacSHA256");
  encoder=new NimbusJwtEncoder(new ImmutableSecret<>(signingKey));
  // 서명·발급자·만료 시간을 검증하고 만료 뒤 유예 시간을 허용하지 않는다.
  var configured=NimbusJwtDecoder.withSecretKey(signingKey).macAlgorithm(MacAlgorithm.HS256).build();
  configured.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),new JwtIssuerValidator(ISSUER)));
  decoder=configured;this.registry=registry;this.accounts=accounts;ttl=Duration.ofSeconds(seconds);
 }
 /** 현재 계정 revision으로 JWT를 만들고 JTI를 Redis에 등록한다. Redis 장애 시 토큰을 발급하지 않는다. */
 public String create(AdminPrincipal principal){
  if(!accounts.current(principal))throw new ApiException(401,"계정 정보가 변경되었습니다. 다시 로그인하세요.");
  Instant now=Instant.now();String id=UUID.randomUUID().toString();
  var claims=JwtClaimsSet.builder().issuer(ISSUER).subject(principal.id()).id(id).issuedAt(now).expiresAt(now.plus(ttl)).claim("revision",principal.revision()).build();
  String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
  try{registry.put(id,principal.id(),ttl);}catch(org.springframework.dao.DataAccessException e){throw unavailable();}
  return token;
 }
 /** 서명, Redis의 JTI·계정 연결, DB의 최신 revision이 모두 맞아야 인증한다. */
 public AdminPrincipal authenticate(String token){
  Jwt jwt=valid(token);String active;
  try{active=registry.get(jwt.getId());}catch(org.springframework.dao.DataAccessException e){throw unavailable();}
  if(!jwt.getSubject().equals(active))throw invalid();
  AdminPrincipal principal;try{principal=accounts.principalById(jwt.getSubject());}catch(UsernameNotFoundException e){throw invalid();}
  Object revision=jwt.getClaim("revision");if(!(revision instanceof Long||revision instanceof Integer)||((Number)revision).longValue()<1||principal.revision()!=((Number)revision).longValue())throw invalid();return principal;
 }
 // 로그아웃은 Redis 등록을 제거한다. 서명이 틀리거나 이미 만료된 토큰은 무시한다.
 public void revoke(String token){
  if(token==null||token.isBlank())return;Jwt jwt;try{jwt=valid(token);}catch(ApiException e){return;}
  try{registry.remove(jwt.getId());}catch(org.springframework.dao.DataAccessException e){throw unavailable();}
 }
 private Jwt valid(String token){
  if(token==null||token.length()>4096)throw invalid();
  try{Jwt jwt=decoder.decode(token);if(jwt.getId()==null||jwt.getSubject()==null||jwt.getExpiresAt()==null||!jwt.getSubject().matches("[0-9a-f-]{36}")||!jwt.getId().matches("[0-9a-f-]{36}"))throw invalid();return jwt;}catch(JwtException|IllegalArgumentException e){throw invalid();}
 }
 private static ApiException invalid(){return new ApiException(401,"로그인이 만료되었거나 계정 정보가 변경되었습니다. 다시 로그인하세요.");}
 private static ApiException unavailable(){return new ApiException(503,"로그인 저장소에 연결할 수 없습니다. 잠시 후 다시 시도하세요.");}
}
