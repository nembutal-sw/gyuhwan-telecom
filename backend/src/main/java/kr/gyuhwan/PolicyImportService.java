package kr.gyuhwan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
// 엑셀 가격표를 제한된 XML/ZIP으로 읽고 현재 저장본과 비교한 뒤 전체 정책을 원자적으로 갱신한다.
public class PolicyImportService {
 private static final String STALE="다른 화면에서 먼저 수정했습니다. 입력 내용을 보관한 뒤 다시 불러오세요.";
 private static final Set<String> CONFIG_FIELDS=Set.of("offers","colors","colorImages");
 private static final Set<String> EXPORT_FIELDS=Set.of("config","revision","updatedAt","canEdit");
 private final ConfigService configs;
 private final QuoteValidator validator;
 private final ObjectMapper mapper;
 private final SiteContentService content;
 private final CatalogService catalog;

 public PolicyImportService(ConfigService configs,QuoteValidator validator,ObjectMapper mapper,SiteContentService content,CatalogService catalog)throws IOException {
  this.configs=configs;
  this.validator=validator;
  this.mapper=mapper;
  this.content=content;
  this.catalog=catalog;
 }

 public record FieldChange(String field,JsonNode before,JsonNode after,boolean beforePresent,boolean afterPresent){}
 public record OfferChange(String key,int modelId,String modelName,String carrier,String type,String discount,List<FieldChange> fields){}
 public record Preview(long revision,int offerCount,int changedOfferCount,int changedFieldCount,List<OfferChange> changes){}
 public record Result(long revision,String updatedAt,int offerCount,int changedOfferCount,int changedFieldCount){}
 private record Prepared(ObjectNode config,Preview preview,String updatedAt){}

 // 정책 JSON 형태가 필요한 내부 호출용 도우미. 관리자 다운로드 경로는 PolicyWorkbookExporter를 사용한다.
 public ObjectNode exportPolicy(){
  ObjectNode policy=mapper.createObjectNode();
  policy.set("offers",((JsonNode)configs.read(true).get("config")).path("offers").deepCopy());
  return policy;
 }

 public Preview preview(long revision,JsonNode policy){return prepare(revision,policy).preview();}

 // 미리보기는 검증과 차이 계산만 수행하며 DB 쓰기는 하지 않는다.
 public Preview previewExcel(long revision,String fileName,String base64){return preview(revision,excelPolicy(revision,fileName,base64));}

 // 적용할 때 파일을 다시 읽고 현재 revision도 다시 확인한다. 미리보기 후 다른 저장이 생기면 409다.
 @Transactional
 public Result importExcel(long revision,String fileName,String base64){return importPolicy(revision,excelPolicy(revision,fileName,base64));}

 private JsonNode excelPolicy(long revision,String fileName,String base64){
  require(revision>=1,"저장 버전 오류");
  Map<String,Object> current=configs.read(true);
  if(((Number)current.get("revision")).longValue()!=revision)throw new ApiException(409,STALE);
  return new ExcelReader(mapper,(JsonNode)current.get("config"),catalog.ids()).read(fileName,base64);
 }

 // 여러 모델·조건을 한 번의 ConfigService.save()로 저장해 부분 반영을 방지한다.
 @Transactional
 public Result importPolicy(long revision,JsonNode policy){
  Prepared prepared=prepare(revision,policy);
  Preview preview=prepared.preview();
  if(preview.changedOfferCount()==0)return new Result(revision,prepared.updatedAt(),preview.offerCount(),0,0);
  Map<String,Object> saved=configs.save(revision,prepared.config(),"PRICE_EXCEL_IMPORT");
  return new Result(((Number)saved.get("revision")).longValue(),(String)saved.get("updatedAt"),preview.offerCount(),preview.changedOfferCount(),preview.changedFieldCount());
 }

 // 기존 정책을 복사해 엑셀에 들어 있는 조건만 덮는다. 파일에 없는 조건은 원래 값을 유지한다.
 // 기존 필드의 빈 셀은 해당 값을 비우는 입력으로 해석한다.
 // 화면 미리보기에는 모델명·통신사·전후 값을 포함한 변경 목록을 만든다.
 private Prepared prepare(long revision,JsonNode policy){
  require(revision>=1,"저장 버전 오류");
  Map<String,Object> current=configs.read(true);
  if(((Number)current.get("revision")).longValue()!=revision)throw new ApiException(409,STALE);
  ObjectNode imported=importedOffers(policy);
  validateImportedFields(imported);
  ObjectNode config=((JsonNode)current.get("config")).deepCopy();
  ObjectNode offers=(ObjectNode)config.path("offers");
  List<OfferChange> changes=new ArrayList<>();
  Map<Integer,String> currentNames=new HashMap<>();
  for(JsonNode product:content.resource("products.json"))currentNames.put(product.path("id").asInt(),product.path("name").asText());
  int fieldCount=0;
  var entries=imported.fields();
  while(entries.hasNext()){
   var entry=entries.next();
   String key=entry.getKey();
   ObjectNode patch=(ObjectNode)entry.getValue();
   ObjectNode before=offers.path(key).isObject()?((ObjectNode)offers.path(key)).deepCopy():mapper.createObjectNode();
   ObjectNode after=before.deepCopy();
   patch.fields().forEachRemaining(field->{
    if(field.getKey().equals("addonSchedule")&&field.getValue().isNull())after.remove("addonSchedule");
    else after.set(field.getKey(),field.getValue().deepCopy());
   });
   normalizeSubsidy(patch,after);
   String[] conditions=key.split("\\|",-1);
   boolean principalChanged=!Objects.equals(amount(before,"retail"),amount(after,"retail"))||!Objects.equals(amount(before,"support"),amount(after,"support"))||conditions[3].equals("common")&&!Objects.equals(amount(before,"common"),amount(after,"common"));
   boolean feeChanged=patch.has("installmentFee")&&!Objects.equals(before.get("installmentFee"),after.get("installmentFee"));
   if(principalChanged||feeChanged){
    Long fee=installmentFee(after,conditions[3]);
    if(fee==null)after.putNull("installmentFee");else after.put("installmentFee",fee.intValue());
   }
   offers.set(key,after);
   List<FieldChange> fields=changes(before,after);
   if(!fields.isEmpty()){
    int id=Integer.parseInt(conditions[0]);
    changes.add(new OfferChange(key,id,currentNames.get(id),conditions[1],conditions[2],conditions[3],List.copyOf(fields)));
    fieldCount+=fields.size();
   }
  }
  validator.validate(config);
  return new Prepared(config,new Preview(revision,imported.size(),changes.size(),fieldCount,List.copyOf(changes)),(String)current.get("updatedAt"));
 }

 private ObjectNode importedOffers(JsonNode policy){
  require(policy!=null&&policy.isObject(),"가격 정책 JSON 객체를 선택하세요.");
  JsonNode config=policy;
  if(policy.has("config")){
   requireFields(policy,EXPORT_FIELDS);
   config=policy.path("config");
   require(config.isObject(),"정책 파일의 config 형식을 확인하세요.");
  }
  requireFields(config,CONFIG_FIELDS);
  JsonNode offers=config.path("offers");
  require(offers.isObject()&&!offers.isEmpty(),"가격 정책 offers 항목이 비어 있거나 올바르지 않습니다.");
  offers.elements().forEachRemaining(offer->require(offer.isObject()&&!offer.isEmpty(),"가격 정책 항목에는 변경할 필드를 입력하세요."));
  return (ObjectNode)offers;
 }

 // 문의 표시의 음수 지원금 정규화 전에 원본 금액 범위를 먼저 검증한다.
 private void validateImportedFields(ObjectNode imported){
  ObjectNode validation=mapper.createObjectNode();
  ObjectNode offers=imported.deepCopy();
  offers.elements().forEachRemaining(offer->{
   // 원본 지원금 검사 전에 문의 표시가 금액을 0으로 바꾸지 않도록 이 복사본에서는 표시를 끈다.
   if(offer.path("supportInquiry").isBoolean()&&offer.path("supportInquiry").asBoolean())((ObjectNode)offer).put("supportInquiry",false);
   if(offer.path("addonSchedule").isNull())((ObjectNode)offer).remove("addonSchedule");
  });
  validation.set("offers",offers);
  validation.putObject("colors");
  validator.validate(validation);
 }

 // 음수 추가 지원금은 고객에게 금액 대신 '문의'를 표시하도록 0원과 문의 플래그로 정규화한다.
 private void normalizeSubsidy(ObjectNode patch,ObjectNode offer){
  if(!patch.has("support")&&!patch.has("supportInquiry"))return;
  if(offer.path("support").isIntegralNumber()&&offer.path("support").asLong()<0)offer.put("supportInquiry",true);
  if(offer.path("supportInquiry").asBoolean(false))offer.put("support",0);
 }

 // 원금/지원금이 바뀌면 연 5.9%, 24개월 기준의 총 할부이자를 다시 계산한다.
 private Long installmentFee(ObjectNode offer,String discount){
  Long retail=amount(offer,"retail"),support=amount(offer,"support"),common=discount.equals("choice")?0L:amount(offer,"common");
  if(retail==null||support==null||common==null)return null;
  long principal=retail-common-support;
  if(principal<0||principal>100000000)return null;
  if(principal==0)return 0L;
  double monthlyRate=0.059/12;
  double payment=principal*monthlyRate/(1-Math.pow(1+monthlyRate,-24));
  return Math.round(payment*24-principal);
 }

 private static Long amount(JsonNode offer,String field){
  JsonNode value=offer.path(field);
  return value.isIntegralNumber()?value.longValue():null;
 }

 private List<FieldChange> changes(ObjectNode before,ObjectNode after){
  Set<String> fields=new LinkedHashSet<>();
  before.fieldNames().forEachRemaining(fields::add);
  after.fieldNames().forEachRemaining(fields::add);
  List<FieldChange> changes=new ArrayList<>();
  for(String field:fields){
   JsonNode previous=before.get(field),next=after.get(field);
   if(!Objects.equals(previous,next))changes.add(new FieldChange(field,previous==null?NullNode.getInstance():previous.deepCopy(),next==null?NullNode.getInstance():next.deepCopy(),previous!=null,next!=null));
  }
  return changes;
 }

 private static void requireFields(JsonNode object,Set<String> allowed){object.fieldNames().forEachRemaining(field->require(allowed.contains(field),"알 수 없는 정책 파일 항목: "+field));}
 private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}

 // 엑셀 가격 정책 시트만 읽는다. 수식은 실행하지 않고 ZIP 경로를 파일 시스템에 풀지 않는다.
 private static class ExcelReader {
  private static final int MAX_FILE_BYTES=1_500_000,MAX_XML_BYTES=40_000_000;
  private static final String[] FIELDS={"retail","common","support","supportInquiry","installmentFee","planName","planFee","discountBase","addonFee","addonMonths","addonSchedule","planDetails","addonDetails","penaltyDetails","terms"};
  private final ObjectMapper mapper;
  private final JsonNode current;
  private final Set<Integer> modelIds;
  private record Cell(String text,String type){}

  ExcelReader(ObjectMapper mapper,JsonNode current,Set<Integer> modelIds){this.mapper=mapper;this.current=current;this.modelIds=modelIds;}

  // 파일 크기, 필수 시트와 내부 연결 대상의 형식을 확인한 뒤 셀을 정책 JSON으로 변환한다.
  ObjectNode read(String fileName,String base64){
   require(fileName!=null&&fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx"),"다운로드한 .xlsx 엑셀 파일을 선택하세요.");
   require(base64!=null&&!base64.isBlank()&&base64.length()<=2_000_000,"엑셀 파일은 1.5MB 이하로 업로드하세요.");
   byte[] bytes;
   try{bytes=Base64.getDecoder().decode(base64);}catch(IllegalArgumentException e){throw new IllegalArgumentException("엑셀 파일 인코딩이 올바르지 않습니다.");}
   require(bytes.length>0&&bytes.length<=MAX_FILE_BYTES,"엑셀 파일은 1.5MB 이하로 업로드하세요.");
   Map<String,byte[]> archive=archive(bytes);
   Document workbook=xml(required(archive,"xl/workbook.xml"));
   Document relationships=xml(required(archive,"xl/_rels/workbook.xml.rels"));
   String sheetId=null;
   var sheets=workbook.getElementsByTagNameNS("*","sheet");
   for(int i=0;i<sheets.getLength();i++){
    Element sheet=(Element)sheets.item(i);
    if(!sheet.getAttribute("name").equals("가격 정책"))continue;
    require(sheetId==null,"가격 정책 시트가 중복되어 있습니다.");
    var attributes=sheet.getAttributes();
    for(int j=0;j<attributes.getLength();j++)if("id".equals(attributes.item(j).getLocalName()))sheetId=attributes.item(j).getNodeValue();
   }
   require(sheetId!=null,"가격 정책 시트가 없습니다. 관리자에서 다운로드한 엑셀을 사용하세요.");
   String worksheet=null,sharedPath=null;
   var relations=relationships.getElementsByTagNameNS("*","Relationship");
   for(int i=0;i<relations.getLength();i++){
    Element relation=(Element)relations.item(i);
    if(relation.getAttribute("Id").equals(sheetId)){
     require(!relation.getAttribute("TargetMode").equalsIgnoreCase("External")&&relation.getAttribute("Type").endsWith("/worksheet"),"외부 엑셀 시트는 가져올 수 없습니다.");
     worksheet=target(relation.getAttribute("Target"));
    }
    if(relation.getAttribute("Type").endsWith("/sharedStrings")){
     require(!relation.getAttribute("TargetMode").equalsIgnoreCase("External"),"외부 문자열 자료는 가져올 수 없습니다.");
     sharedPath=target(relation.getAttribute("Target"));
    }
   }
   require(worksheet!=null,"가격 정책 시트 연결 정보가 올바르지 않습니다.");
   List<String> strings=new ArrayList<>();
   if(sharedPath!=null){
    var items=xml(required(archive,sharedPath)).getElementsByTagNameNS("*","si");
    for(int i=0;i<items.getLength();i++)strings.add(stringText((Element)items.item(i)));
   }
   return policy(xml(required(archive,worksheet)),strings);
  }

  // 3행의 고정 열 제목과 4행 이후 모델·통신사·가입·할인 키를 확인한다.
  // 같은 조건의 중복 행과 낯선 열은 거부해 엑셀 입력 실수로 다른 정책이 바뀌지 않게 한다.
  private ObjectNode policy(Document sheet,List<String> strings){
   ObjectNode offers=mapper.createObjectNode();
   Set<String> keys=new LinkedHashSet<>();
   Set<Integer> rowNumbers=new LinkedHashSet<>();
   boolean header=false;
   int previousRow=0;
   var rows=sheet.getElementsByTagNameNS("*","row");
   require(rows.getLength()<=CatalogService.MAX_OFFERS+3,"엑셀 가격 정책은 최대 5,400행까지 읽을 수 있습니다.");
   for(int i=0;i<rows.getLength();i++){
    Element row=(Element)rows.item(i);
    int number=row.hasAttribute("r")?integerAttribute(row.getAttribute("r")):previousRow+1;
    require(number>0&&rowNumbers.add(number),"엑셀 행 번호가 올바르지 않습니다.");
    previousRow=number;
    Map<Integer,Cell> cells=cells(row,number,strings);
    if(number<3)continue;
    if(number==3){
     for(int column=0;column<PolicyWorkbookExporter.POLICY_HEADERS.length;column++)require(text(cells,column).trim().equals(PolicyWorkbookExporter.POLICY_HEADERS[column]),"엑셀 열 제목을 유지하세요. 관리자에서 다운로드한 양식을 사용하세요.");
     for(var entry:cells.entrySet())if(entry.getKey()>=PolicyWorkbookExporter.POLICY_HEADERS.length)require(entry.getValue().text().isBlank(),"알 수 없는 엑셀 가격 정책 열이 있습니다.");
     header=true;
     continue;
    }
    require(header,"엑셀 가격 정책 열 제목이 없습니다.");
    if(cells.values().stream().allMatch(cell->cell.text().isBlank()))continue;
    for(var entry:cells.entrySet())if(entry.getKey()>=PolicyWorkbookExporter.POLICY_HEADERS.length)require(entry.getValue().text().isBlank(),"알 수 없는 엑셀 가격 정책 열이 있습니다.");
    long model=integer(cell(cells,0),number,"ID");
    require(model>=0&&model<=Integer.MAX_VALUE&&modelIds.contains((int)model),"엑셀 "+number+"행 모델 ID를 확인하세요.");
    String carrier=text(cells,5).trim(),type=text(cells,6).trim(),discount=text(cells,7).trim();
    String key=model+"|"+carrier+"|"+type+"|"+discount;
    require(keys.add(key),"엑셀 "+number+"행의 모델·통신사·가입 유형·할인 방법이 중복됩니다.");
    // 미등록 행을 건너뛰더라도 식별 열의 값은 먼저 검증한다.
    require(Set.of("SKT","KT","LG U+").contains(carrier)&&Set.of("번호이동","기기변경","신규가입").contains(type)&&Set.of("common","choice").contains(discount),"엑셀 "+number+"행의 통신사·가입 유형·할인 방법을 확인하세요.");
    JsonNode before=current.path("offers").path(key);
    boolean entered=false;
    for(int column=9;column<24;column++)if(!text(cells,column).isBlank())entered=true;
    if(!entered&&(!before.isObject()||text(cells,8).trim().equals("미등록")))continue;
    ObjectNode offer=mapper.createObjectNode();
    for(int column=9;column<24;column++){
     String field=FIELDS[column-9];
     Cell value=cell(cells,column);
     boolean blank=value.text().isBlank();
     if(blank&&!before.has(field))continue;
     if(QuoteValidator.AMOUNTS.contains(field)){
      if(blank)offer.putNull(field);
      else{
       long numberValue=integer(value,number,PolicyWorkbookExporter.POLICY_HEADERS[column]);
       if(numberValue>=Integer.MIN_VALUE&&numberValue<=Integer.MAX_VALUE)offer.put(field,(int)numberValue);else offer.put(field,numberValue);
      }
     }else if(field.equals("supportInquiry")){
      if(blank)offer.put(field,false);else offer.put(field,bool(value,number));
     }else if(field.equals("addonSchedule")){
      if(blank)offer.putNull(field);
      else{
       try{offer.set(field,mapper.readTree(value.text()));}catch(IOException e){throw new IllegalArgumentException("엑셀 "+number+"행의 부가서비스 월별 금액 JSON을 확인하세요.");}
      }
     }else offer.put(field,value.text());
    }
    if(!offer.isEmpty())offers.set(key,offer);
   }
   require(header,"엑셀 가격 정책 열 제목이 없습니다.");
   require(!offers.isEmpty(),"엑셀에 업로드할 가격 정책이 없습니다.");
   ObjectNode policy=mapper.createObjectNode();
   policy.set("offers",offers);
   return policy;
  }

  // 공유 문자열·인라인 문자열·숫자·불리언만 허용하고 수식/오류 셀을 거부한다.
  private Map<Integer,Cell> cells(Element row,int number,List<String> strings){
   Map<Integer,Cell> cells=new HashMap<>();
   int previous=-1;
   var children=row.getChildNodes();
   for(int i=0;i<children.getLength();i++){
    if(!(children.item(i) instanceof Element cell)||!"c".equals(cell.getLocalName()))continue;
    require(cell.getElementsByTagNameNS("*","f").getLength()==0,"엑셀 "+number+"행에 수식이 있습니다. 수식 대신 계산된 값을 붙여넣으세요.");
    String reference=cell.getAttribute("r");
    int column=reference.isEmpty()?previous+1:column(reference,number);
    require(column<100&&cells.get(column)==null,"엑셀 셀 위치가 올바르지 않습니다.");
    previous=column;
    String type=cell.getAttribute("t"),value=childText(cell,"v");
    if(type.equals("inlineStr")){
     Element inline=child(cell,"is");
     value=inline==null?"":stringText(inline);
    }else if(type.equals("s")){
     int index=integerAttribute(value);
     require(index>=0&&index<strings.size(),"엑셀 문자열 번호가 올바르지 않습니다.");
     value=strings.get(index);
    }else if(type.equals("b")){
     require(value.equals("0")||value.equals("1"),"엑셀 참/거짓 값이 올바르지 않습니다.");
     value=value.equals("1")?"true":"false";
    }else require(type.isEmpty()||Set.of("n","str").contains(type),"엑셀 "+number+"행에 오류 또는 지원하지 않는 셀 값이 있습니다.");
    cells.put(column,new Cell(value,type));
   }
   return cells;
  }

  private static long integer(Cell cell,int row,String name){
   String value=cell.text().trim();
   try{
    require(!value.isEmpty()&&!cell.type().equals("b"),"엑셀 "+row+"행 "+name+" 값은 원 단위 정수여야 합니다.");
    if(!cell.type().isEmpty()&&!cell.type().equals("n"))require(value.matches("-?(?:\\d+|\\d{1,3}(?:,\\d{3})+)"),"엑셀 "+row+"행 "+name+" 값은 원 단위 정수여야 합니다.");
    return new BigDecimal(value.replace(",","")).longValueExact();
   }catch(NumberFormatException|ArithmeticException e){throw new IllegalArgumentException("엑셀 "+row+"행 "+name+" 값은 원 단위 정수여야 합니다.");}
  }

  private static boolean bool(Cell value,int row){
   return switch(value.text().trim().toLowerCase(java.util.Locale.ROOT)){
    case "예","true","1"->true;
    case "아니오","false","0"->false;
    default->throw new IllegalArgumentException("엑셀 "+row+"행의 지원금 문의 값은 예 또는 아니오로 입력하세요.");
   };
  }

  private static int column(String reference,int row){
   require(reference.matches("[A-Z]+[0-9]+"),"엑셀 셀 주소가 올바르지 않습니다.");
   int column=0,index=0;
   while(index<reference.length()&&Character.isUpperCase(reference.charAt(index))){
    column=column*26+reference.charAt(index++)-'A'+1;
    require(column<=100,"엑셀 가격 정책 열 범위를 확인하세요.");
   }
   require(integerAttribute(reference.substring(index))==row,"엑셀 행과 셀 주소가 일치하지 않습니다.");
   return column-1;
  }

  private static int integerAttribute(String value){
   try{return Integer.parseInt(value);}catch(NumberFormatException e){throw new IllegalArgumentException("엑셀 행 또는 셀 번호가 올바르지 않습니다.");}
  }
  private static Cell cell(Map<Integer,Cell> cells,int column){return cells.getOrDefault(column,new Cell("",""));}
  private static String text(Map<Integer,Cell> cells,int column){return cell(cells,column).text();}
  private static Element child(Element element,String name){
   var children=element.getChildNodes();
   for(int i=0;i<children.getLength();i++)if(children.item(i) instanceof Element child&&name.equals(child.getLocalName()))return child;
   return null;
  }
  private static String childText(Element element,String name){Element child=child(element,name);return child==null?"":child.getTextContent();}
  private static String stringText(Element element){
   StringBuilder text=new StringBuilder();
   var children=element.getChildNodes();
   for(int i=0;i<children.getLength();i++)if(children.item(i) instanceof Element child){
    if("t".equals(child.getLocalName()))text.append(child.getTextContent());
    else if("r".equals(child.getLocalName()))text.append(childText(child,"t"));
   }
   return text.toString();
  }

  // 워크북의 내부 관계 경로가 상대 경로 탈출 또는 외부 주소가 되지 않게 제한한다.
  private static String target(String target){
   require(target!=null&&!target.isBlank()&&!target.contains("\\")&&!target.contains(":"),"엑셀 내부 자료 경로가 올바르지 않습니다.");
   String path=target.startsWith("/")?target.substring(1):"xl/"+target;
   require(java.util.Arrays.stream(path.split("/",-1)).noneMatch(part->part.equals("..")||part.equals(".")||part.isEmpty()),"엑셀 내부 자료 경로가 올바르지 않습니다.");
   return path;
  }

  // ZIP 엔트리 수와 전체 압축 해제량을 제한해 작은 파일을 가장한 압축 폭탄을 거부한다.
  private static Map<String,byte[]> archive(byte[] file){
   Map<String,byte[]> archive=new HashMap<>();
   int total=0,count=0;
   try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(file))){
    ZipEntry entry;
    byte[] buffer=new byte[8192];
    while((entry=zip.getNextEntry())!=null){
     require(++count<=100,"엑셀 내부 파일 수가 너무 많습니다.");
     if(entry.isDirectory())continue;
     require(!archive.containsKey(entry.getName()),"엑셀 내부 자료가 중복되어 있습니다.");
     ByteArrayOutputStream data=new ByteArrayOutputStream();
     int length;
     while((length=zip.read(buffer))!=-1){
      total+=length;
      require(total<=MAX_XML_BYTES,"엑셀 압축 해제 용량이 너무 큽니다.");
      data.write(buffer,0,length);
     }
     archive.put(entry.getName(),data.toByteArray());
    }
   }catch(IOException e){throw new IllegalArgumentException("엑셀 파일의 압축 구조가 올바르지 않습니다.");}
   return archive;
  }
  private static byte[] required(Map<String,byte[]> archive,String name){
   byte[] bytes=archive.get(name);
   require(bytes!=null,"엑셀 내부 필수 자료가 없습니다: "+name);
   return bytes;
  }
  // DTD·외부 엔터티·외부 스키마를 끄고 XML을 파싱해 XXE를 막는다.
  private static Document xml(byte[] bytes){
   try{
    DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    var builder=factory.newDocumentBuilder();
    builder.setErrorHandler(new DefaultHandler(){@Override public void error(SAXParseException e)throws SAXException{throw e;}@Override public void fatalError(SAXParseException e)throws SAXException{throw e;}});
    return builder.parse(new ByteArrayInputStream(bytes));
   }catch(Exception e){throw new IllegalArgumentException("엑셀 XML 자료가 올바르지 않습니다.");}
  }
 }
}
