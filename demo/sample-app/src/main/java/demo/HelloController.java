package demo;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    private final CustomerRepository customers;

    public HelloController(CustomerRepository customers) {
        this.customers = customers;
    }

    @GetMapping("/hello/{name}")
    public ResponseEntity<String> hello(@PathVariable String name) throws Exception {
        customers.findByLastName(name);
        maybeSlowOrFail();
        return ResponseEntity.ok("Hello " + name);
    }

    static void maybeSlowOrFail() throws Exception {
        if (Math.random() > 0.8) {
            Thread.sleep((long) (Math.random() * 5000));
        }
        if (Math.random() > 0.9) {
            throw new Exception("demo induced error");
        }
    }
}
