package kr.gyuhwan;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@RestController
// 관리자 사진 업로드와 공개 사진 제공. 파일은 MEDIA_PATH에 저장하며 이름은 서버가 새 UUID로 만든다.
public class SiteMediaController {
 private static final Logger log=LoggerFactory.getLogger(SiteMediaController.class);
 private static final int MAX_IMAGE_BYTES=1_500_000;
 private static final Duration ORPHAN_GRACE=Duration.ofDays(7);
 private static final Pattern MEDIA_NAME=Pattern.compile("[a-f0-9-]{36}\\.(?:png|jpg|webp)");
 private static final Pattern MEDIA_REFERENCE=Pattern.compile("/media/([a-f0-9-]{36}\\.(?:png|jpg|webp))");
 private final Path directory;
 private final long maxStoredBytes;
 private final AuditLogService audit;
 private final JdbcTemplate db;
 private final TokenRegistry registry;
 private final Semaphore decoder=new Semaphore(1);
 private final Object fileLock=new Object();

 public SiteMediaController(@Value("${app.media-path:./data/media}") String path,
                            @Value("${app.media-max-bytes:268435456}") long maxStoredBytes,
                            AuditLogService audit,JdbcTemplate db,TokenRegistry registry){
  directory=Path.of(path).toAbsolutePath().normalize();
  if(maxStoredBytes<MAX_IMAGE_BYTES)throw new IllegalArgumentException("Media storage limit is too small");
  this.maxStoredBytes=maxStoredBytes;this.audit=audit;this.db=db;this.registry=registry;
 }

 record Upload(String fileName,String base64){}

 // SecurityConfig의 인증·CSRF 검사 뒤 실행된다. Redis 공유 카운터로 관리자별 시간당 업로드를 제한한다.
 // 본문·픽셀 수·동시 디코딩·저장 용량을 각각 제한하고 기록 실패 시 파일도 지운다.
 @PostMapping("/api/admin/content/images") Map<String,String> upload(@RequestBody Upload input,Authentication authentication,HttpServletResponse response)throws IOException{
  if(authentication==null||!authentication.isAuthenticated())throw new ApiException(401,"다시 로그인하세요.");
  Object actor=authentication.getPrincipal();
  String accountId=actor instanceof AdminPrincipal principal?principal.id():authentication.getName();
  if(!registry.uploadAllowed(accountId)){
   response.setHeader("Retry-After","3600");
   throw new ApiException(429,"사진 업로드는 관리자당 한 시간에 30장까지 가능합니다. 잠시 후 다시 시도해 주세요.");
  }
  if(input==null||input.base64()==null||input.base64().length()>2_000_000)throw new IllegalArgumentException("사진은 1.5MB 이하로 업로드해 주세요.");
  byte[] bytes;
  try{bytes=Base64.getDecoder().decode(input.base64());}catch(IllegalArgumentException e){throw new IllegalArgumentException("사진 파일을 읽을 수 없습니다.");}
  if(bytes.length>MAX_IMAGE_BYTES||bytes.length<12)throw new IllegalArgumentException("사진은 1.5MB 이하의 PNG, JPEG, WebP 파일이어야 합니다.");
  String extension;
  if(Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10}))extension="png";
  else if((bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255)extension="jpg";
  else if(new String(bytes,0,4,StandardCharsets.US_ASCII).equals("RIFF")&&new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WEBP"))extension="webp";
  else throw new IllegalArgumentException("PNG, JPEG, WebP 사진만 업로드할 수 있습니다.");
  if(!decoder.tryAcquire()){
   response.setHeader("Retry-After","2");
   throw new ApiException(429,"다른 사진을 처리하고 있습니다. 잠시 후 다시 올려 주세요.");
  }
  try{bytes=extension.equals("webp")?sanitizeWebp(bytes):reencodeImage(bytes,extension);}finally{decoder.release();}
  if(bytes.length>MAX_IMAGE_BYTES)throw new IllegalArgumentException("사진을 처리한 뒤 용량이 1.5MB를 넘었습니다. 크기를 줄여 다시 올려 주세요.");
  String name=UUID.randomUUID()+"."+extension;
  Path saved=directory.resolve(name);
  synchronized(fileLock){
   Files.createDirectories(directory);
   enforceStorageLimit(bytes.length);
   Files.write(saved,bytes,StandardOpenOption.CREATE_NEW);
  }
  try{audit.record("IMAGE_UPLOAD","메인 페이지 사진 · "+name,List.of(AuditLogService.change("image","업로드 사진",null,Map.of("url","/media/"+name,"bytes",bytes.length))));}
  catch(RuntimeException error){Files.deleteIfExists(saved);throw error;}
  return Map.of("url","/media/"+name,"fileName",name);
 }

 // PNG/JPEG를 새 픽셀 버퍼와 새 인코더로 작성해 EXIF·GPS·XMP·텍스트 메타데이터를 공개 파일에서 제거한다.
 private static byte[] reencodeImage(byte[] bytes,String extension)throws IOException{
  try(var stream=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))){
   var readers=ImageIO.getImageReaders(stream);
   if(!readers.hasNext())throw new IllegalArgumentException("사진 파일이 손상되었습니다.");
   var reader=readers.next();
   try{
    reader.setInput(stream,true,true);
    int width=reader.getWidth(0),height=reader.getHeight(0);
    dimensions(width,height);
    BufferedImage source=reader.read(0);
    if(source==null)throw new IllegalArgumentException("사진 파일이 손상되었습니다.");
    // 원본 메타데이터나 색상 프로필 객체를 재사용하지 않는다.
    BufferedImage clean=new BufferedImage(width,height,extension.equals("jpg")?BufferedImage.TYPE_INT_RGB:BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics=clean.createGraphics();
    try{graphics.drawImage(source,0,0,null);}finally{graphics.dispose();source.flush();}
    ByteArrayOutputStream out=new ByteArrayOutputStream(Math.min(bytes.length,MAX_IMAGE_BYTES));
    if(!ImageIO.write(clean,extension.equals("jpg")?"jpeg":"png",out))throw new IllegalArgumentException("사진 파일을 처리할 수 없습니다.");
    clean.flush();
    return out.toByteArray();
   }finally{reader.dispose();}
  }catch(javax.imageio.IIOException|IndexOutOfBoundsException e){throw new IllegalArgumentException("사진 파일이 손상되었습니다.");}
 }

 // 작은 압축 파일이 지나치게 큰 픽셀 버퍼를 할당하게 만들지 않는다.
 private static void dimensions(long width,long height){
  if(width<1||height<1||width>8000||height>8000||width*height>25_000_000)
   throw new IllegalArgumentException("사진은 가로·세로 8,000px, 총 2,500만 픽셀 이하로 올려 주세요.");
 }
 private static long little(byte[] bytes,int offset,int count){long value=0;for(int i=0;i<count;i++)value|=(long)(bytes[offset+i]&255)<<(i*8);return value;}
 private static void putLittle(byte[] bytes,int offset,long value){for(int i=0;i<4;i++)bytes[offset+i]=(byte)(value>>>(i*8));}

 // 기본 Java 이미지 인코더에는 WebP 쓰기 기능이 없어 RIFF 컨테이너의 이미지 청크만 다시 조립한다.
 // EXIF/XMP/ICC 및 알 수 없는 부가 청크를 버리고 애니메이션은 거부한다.
 private static byte[] sanitizeWebp(byte[] bytes){
  if(little(bytes,4,4)+8!=bytes.length)throw new IllegalArgumentException("WebP 사진 파일이 손상되었습니다.");
  ByteArrayOutputStream out=new ByteArrayOutputStream(bytes.length);
  out.writeBytes(Arrays.copyOf(bytes,12));
  int pictures=0,offset=12;
  while(offset+8<=bytes.length){
   String chunk=new String(bytes,offset,4,StandardCharsets.US_ASCII);
   long length=little(bytes,offset+4,4);
   int start=offset+8;
   if(length>bytes.length-start||length+(length&1)>bytes.length-start)throw new IllegalArgumentException("WebP 사진 파일이 손상되었습니다.");
   if(chunk.equals("ANIM")||chunk.equals("ANMF"))throw new IllegalArgumentException("움직이는 WebP 대신 정지 사진을 올려 주세요.");
   if(chunk.equals("VP8 ")){
    if(length<=10||(bytes[start]&1)!=0||(bytes[start+3]&255)!=157||(bytes[start+4]&255)!=1||(bytes[start+5]&255)!=42)throw new IllegalArgumentException("WebP 사진 파일이 손상되었습니다.");
    dimensions(little(bytes,start+6,2)&16383,little(bytes,start+8,2)&16383);pictures++;
   }else if(chunk.equals("VP8L")){
    if(length<=5||(bytes[start]&255)!=47)throw new IllegalArgumentException("WebP 사진 파일이 손상되었습니다.");
    long bits=little(bytes,start+1,4);if((bits>>>29)!=0)throw new IllegalArgumentException("WebP 사진 파일이 손상되었습니다.");
    dimensions((bits&16383)+1,((bits>>>14)&16383)+1);pictures++;
   }else if(chunk.equals("VP8X")){
    if(length!=10||(bytes[start]&0x02)!=0)throw new IllegalArgumentException("정상적인 정지 사진 WebP 파일을 선택해 주세요.");
    dimensions(little(bytes,start+4,3)+1,little(bytes,start+7,3)+1);
   }
   // 화면 표시용 구조와 압축 픽셀만 남긴다. 부가 청크에는 위치나 작성자 정보가 들어갈 수 있다.
   if(Set.of("VP8X","ALPH","VP8 ","VP8L").contains(chunk)){
    byte[] kept=Arrays.copyOfRange(bytes,offset,start+(int)length+(int)(length&1));
    if(chunk.equals("VP8X"))kept[8]&=(byte)~(0x20|0x08|0x04);
    out.writeBytes(kept);
   }
   offset=start+(int)length+(int)(length&1);
  }
  if(pictures!=1||offset!=bytes.length)throw new IllegalArgumentException("정상적인 정지 사진 WebP 파일을 선택해 주세요.");
  byte[] clean=out.toByteArray();putLittle(clean,4,clean.length-8);return clean;
 }

 // MEDIA_PATH의 업로드 파일 총량을 제한한다. 공간이 부족하면 오래된 미참조 파일부터 정리한다.
 private void enforceStorageLimit(long upcoming)throws IOException{
  long used=storedBytes();
  if(used>maxStoredBytes-upcoming){pruneUnusedImagesLocked();used=storedBytes();}
  if(used>maxStoredBytes-upcoming)throw new ApiException(507,"사진 저장 공간이 가득 찼습니다. 운영자에게 저장 공간을 확인해 달라고 요청해 주세요.");
 }
 private long storedBytes()throws IOException{
  long used=0;
  try(Stream<Path> paths=Files.list(directory)){
   for(Path path:paths.toList())if(MEDIA_NAME.matcher(path.getFileName().toString()).matches()&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)){
    used+=Files.size(path);
    if(used<0)throw new IOException("Media storage size overflow");
   }
  }
  return used;
 }
 // 매일 정리하되 실패는 로그에 남기고 서버 요청 처리까지 중단하지 않는다.
 @Scheduled(cron="${app.media-cleanup-cron:0 0 4 * * *}",zone="Asia/Seoul")
 public void scheduledPrune(){
  try{synchronized(fileLock){if(Files.isDirectory(directory))pruneUnusedImagesLocked();}}
  catch(Exception error){log.warn("Unused media cleanup failed; no referenced images were intentionally removed",error);}
 }
 // 저장 전 편집 중인 사진이 바로 사라지지 않도록 7일 유예한다.
 // 현재 콘텐츠와 가격 정책뿐 아니라 과거 저장본에 쓰인 사진도 복원용으로 보존한다.
 private void pruneUnusedImagesLocked()throws IOException{
  Instant cutoff=Instant.now().minus(ORPHAN_GRACE);
  List<Path> candidates=new ArrayList<>();
  try(Stream<Path> paths=Files.list(directory)){
   for(Path path:paths.toList())if(MEDIA_NAME.matcher(path.getFileName().toString()).matches()&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.getLastModifiedTime(path).toInstant().isBefore(cutoff))candidates.add(path);
  }
  if(candidates.isEmpty())return;
  Set<String> referenced=referencedImages();
  candidates.sort(Comparator.comparing(Path::toString));
  for(Path path:candidates)if(!referenced.contains(path.getFileName().toString()))Files.deleteIfExists(path);
 }
 // 네 테이블의 현재/과거 JSON에서 /media/UUID 경로를 찾아 삭제 금지 집합을 만든다.
 private Set<String> referencedImages(){
  Set<String> referenced=new HashSet<>();
  for(String table:List.of("site_content","site_content_history","quote_config","config_history")){
   db.query("SELECT payload FROM "+table,rs->{
    String payload=rs.getString(1);
    if(payload==null)return;
    Matcher links=MEDIA_REFERENCE.matcher(payload);
    while(links.find())referenced.add(links.group(1));
   });
  }
  return referenced;
 }

 // 허용된 서버 생성 파일명과 실제 일반 파일만 공개한다. 브라우저에 MIME 추측을 허용하지 않는다.
 @GetMapping("/media/{fileName}") ResponseEntity<FileSystemResource> image(@PathVariable String fileName){
  if(!MEDIA_NAME.matcher(fileName).matches())throw new ApiException(404,"사진을 찾을 수 없습니다.");
  Path path=directory.resolve(fileName).normalize();
  if(!path.getParent().equals(directory)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new ApiException(404,"사진을 찾을 수 없습니다.");
  String type=fileName.endsWith(".png")?"image/png":fileName.endsWith(".jpg")?"image/jpeg":"image/webp";
  return ResponseEntity.ok().header("Cache-Control","public, max-age=31536000, immutable").header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(type)).body(new FileSystemResource(path));
 }
}
