package kr.gyuhwan;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Set;
/** 모델 편집과 가격표 편집은 같은 상품 ID 목록을 사용한다. 고객 응답에도 판매 상태를 포함한다. */
@RestController public class CatalogController {
 private final SiteContentService content;private final CatalogService catalog;private final ConfigService configs;
 public CatalogController(SiteContentService content,CatalogService catalog,ConfigService configs){this.content=content;this.catalog=catalog;this.configs=configs;}
 @GetMapping("/api/admin/catalog") Map<String,Object> catalog(){
  JsonNode products=content.resource("products.json"),colors=((JsonNode)configs.read(true).get("config")).path("colors");
  for(JsonNode product:products){JsonNode configured=colors.path(product.path("id").asText());if(configured.isArray()){
   var editable=(com.fasterxml.jackson.databind.node.ObjectNode)product;editable.set("colors",configured);Set<String> current=new java.util.HashSet<>();configured.forEach(color->current.add(color.asText()));if(editable.path("colorStatuses").isObject())((com.fasterxml.jackson.databind.node.ObjectNode)editable.path("colorStatuses")).retain(current);
  }}
  return Map.of("products",products,"defaultColors",catalog.colors(),"revision",catalog.revision());
 }
 @GetMapping("/api/public/models") JsonNode models(){return content.resource("products.json");}
 record ModelChange(long revision,JsonNode model){}
 @PostMapping("/api/admin/catalog/models") Map<String,Object> create(@RequestBody ModelChange change){AdminAccess.require("CATALOG_EDIT");return catalog.create(change.revision(),change.model());}
 @PutMapping("/api/admin/catalog/models/{id}") Map<String,Object> update(@PathVariable int id,@RequestBody ModelChange change){AdminAccess.require("CATALOG_EDIT");return catalog.update(id,change.revision(),change.model());}
}
