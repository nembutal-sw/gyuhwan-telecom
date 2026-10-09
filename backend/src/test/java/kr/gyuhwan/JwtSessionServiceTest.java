package kr.gyuhwan;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 여러 인스턴스가 같은 JTI 저장소를 공유할 때의 발급·검증·취소 및 장애 동작을 확인한다. */
class JwtSessionServiceTest {
 private static final String SECRET=Base64.getEncoder().encodeToString("test-only-distributed-signing-secret-at-least-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
 private AdminAccountService accounts;
 private AdminPrincipal principal;
 private MemoryTokenRegistry shared;
 private JwtSessionService first,second;

 @BeforeEach void setup(){
  accounts=mock(AdminAccountService.class);
  principal=new AdminPrincipal(UUID.randomUUID().toString(),"admin","not-a-raw-password",true,1);
  when(accounts.current(principal)).thenReturn(true);when(accounts.principalById(principal.id())).thenReturn(principal);
  shared=new MemoryTokenRegistry();first=new JwtSessionService(shared,accounts,SECRET,1800);second=new JwtSessionService(shared,accounts,SECRET,1800);
 }

 // 두 서비스 인스턴스가 Redis에 해당하는 저장소를 공유하면 다른 인스턴스에서도 취소가 즉시 적용된다.
 @Test void tokenCreatedOnOneInstanceAuthenticatesAndRevokesOnAnother()throws Exception{
  String token=first.create(principal);
  AdminPrincipal authenticated=second.authenticate(token);
  assertEquals(principal.id(),authenticated.id());assertEquals(1,authenticated.revision());assertTrue(authenticated.owner());
  var claims=SignedJWT.parse(token).getJWTClaimsSet();
  assertEquals("gyuhwan-admin",claims.getIssuer());assertEquals(principal.id(),claims.getSubject());assertNotNull(claims.getJWTID());assertEquals(1800,Duration.between(claims.getIssueTime().toInstant(),claims.getExpirationTime().toInstant()).getSeconds());
  assertFalse(claims.toString().contains(principal.getPassword()));assertFalse(claims.getClaims().containsKey("password"));
  second.revoke(token);assertApi(401,()->first.authenticate(token));
 }

 // 계정 revision이 바뀌면 이전 JWT의 서명이 유효해도 인증을 거부한다.
 @Test void changedAccountRevisionInvalidatesExistingTokensAcrossInstances(){
  String token=first.create(principal);
  AdminPrincipal changed=new AdminPrincipal(principal.id(),"renamed","changed-hash",true,2);
  when(accounts.principalById(principal.id())).thenReturn(changed);when(accounts.current(principal)).thenReturn(false);when(accounts.current(changed)).thenReturn(true);
  assertApi(401,()->second.authenticate(token));assertApi(401,()->first.create(principal));
  assertEquals(2,second.authenticate(first.create(changed)).revision());
 }

 @Test void tamperedExpiredWrongIssuerAndMalformedRevisionClaimsAreRejected()throws Exception{
  String token=first.create(principal),jti=SignedJWT.parse(token).getJWTClaimsSet().getJWTID();
  int signatureStart=token.lastIndexOf('.')+1;char original=token.charAt(signatureStart);
  String tampered=token.substring(0,signatureStart)+(original=='A'?'B':'A')+token.substring(signatureStart+1);
  assertApi(401,()->second.authenticate(tampered));
  String wrongIssuer=sign(SECRET,"other-issuer",jti,1L,Instant.now().plusSeconds(300));
  String expired=sign(SECRET,"gyuhwan-admin",jti,1L,Instant.now().minusSeconds(1));
  String stringRevision=sign(SECRET,"gyuhwan-admin",jti,"1",Instant.now().plusSeconds(300));
  String fractionalRevision=sign(SECRET,"gyuhwan-admin",jti,1.5,Instant.now().plusSeconds(300));
  assertApi(401,()->second.authenticate(wrongIssuer));assertApi(401,()->second.authenticate(expired));assertApi(401,()->second.authenticate(stringRevision));assertApi(401,()->second.authenticate(fractionalRevision));
  assertApi(401,()->second.authenticate("not-a-jwt"));
 }

 // JTI 저장소 장애에서는 인증을 허용하지 않고 503으로 실패한다.
 @Test void unavailableRegistryFailsClosedForIssueReadAndRevoke(){
  TokenRegistry registry=mock(TokenRegistry.class);JwtSessionService service=new JwtSessionService(registry,accounts,SECRET,1800);
  doThrow(new DataAccessResourceFailureException("registry offline")).when(registry).put(anyString(),anyString(),any());
  assertApi(503,()->service.create(principal));
  String token=first.create(principal);
  when(registry.get(anyString())).thenThrow(new DataAccessResourceFailureException("registry offline"));
  doThrow(new DataAccessResourceFailureException("registry offline")).when(registry).remove(anyString());
  assertApi(503,()->service.authenticate(token));assertApi(503,()->service.revoke(token));
 }

 @Test void registryAccountBindingAndMissingTokenCannotBeReusedForAnotherAccount()throws Exception{
  String token=first.create(principal),jti=SignedJWT.parse(token).getJWTClaimsSet().getJWTID();
  shared.put(jti,UUID.randomUUID().toString(),Duration.ofMinutes(30));
  assertApi(401,()->second.authenticate(token));
  shared.remove(jti);assertApi(401,()->second.authenticate(token));
 }

 @Test void redisRegistryStoresSharedKeysWithConfiguredExpiryAndRevokesSameKey(){
  StringRedisTemplate redis=mock(StringRedisTemplate.class);
  @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
  when(redis.opsForValue()).thenReturn(values);when(values.get("installation:token:jti")).thenReturn(principal.id());
  RedisTokenRegistry registry=new RedisTokenRegistry(redis,"installation:");Duration ttl=Duration.ofSeconds(120);
  registry.put("jti",principal.id(),ttl);assertEquals(principal.id(),registry.get("jti"));registry.remove("jti");
  verify(values).set("installation:token:jti",principal.id(),ttl);verify(redis).delete("installation:token:jti");
 }

 @Test void shortSecretAndOutOfRangeTokenLifetimeFailAtStartup(){
  assertThrows(IllegalStateException.class,()->new JwtSessionService(shared,accounts,Base64.getEncoder().encodeToString(new byte[16]),1800));
  assertThrows(IllegalStateException.class,()->new JwtSessionService(shared,accounts,SECRET,59));
  assertThrows(IllegalStateException.class,()->new JwtSessionService(shared,accounts,SECRET,86401));
 }

 private String sign(String secret,String issuer,String jti,Object revision,Instant expires){
  var key=new SecretKeySpec(Base64.getDecoder().decode(secret),"HmacSHA256");var encoder=new NimbusJwtEncoder(new ImmutableSecret<>(key));
  var claims=JwtClaimsSet.builder().issuer(issuer).subject(principal.id()).id(jti).issuedAt(Instant.now().minusSeconds(120)).expiresAt(expires).claim("revision",revision).build();
  return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
 }
 private static void assertApi(int status,Runnable operation){assertEquals(status,assertThrows(ApiException.class,operation::run).status);}
}
