package kr.gyuhwan;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class PolicyScheduleController {
 private final PolicyScheduleService service;
 public PolicyScheduleController(PolicyScheduleService service){this.service=service;}
 @GetMapping("/api/admin/schedules") List<Map<String,Object>> list(){return service.list();}
 @GetMapping("/api/public/policy-period") Map<String,Object> active(){return service.active();}
 record Create(String title,long revision,JsonNode config,String startsAt,String endsAt){}
 @PostMapping("/api/admin/schedules") Map<String,Object> create(@RequestBody Create input){return service.create(input.title(),input.revision(),input.config(),input.startsAt(),input.endsAt());}
 @PostMapping("/api/admin/schedules/{id}/cancel") void cancel(@PathVariable String id){service.cancel(id);}
}
