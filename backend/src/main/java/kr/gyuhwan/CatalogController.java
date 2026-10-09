package kr.gyuhwan;
import com.fasterxml.jackson.databind.*;import org.springframework.web.bind.annotation.*;import java.util.Map;
// 가격표 편집기에서 사용하는 모델 목록과 기본 색상. 모델 이름은 저장된 콘텐츠 수정을 반영한다.
@RestController public class CatalogController{private final ObjectMapper mapper;private final SiteContentService content;public CatalogController(ObjectMapper m,SiteContentService c){mapper=m;content=c;}
 @GetMapping("/api/admin/catalog") Map<String,JsonNode> catalog()throws Exception{return Map.of("products",content.resource("products.json"),"defaultColors",mapper.readTree(getClass().getResourceAsStream("/model-colors.json")));}
}
