package demo;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Configuration
class DemoSeed {
    static final List<String> NAMES = List.of("john", "paul", "george", "ringo");

    @Bean
    ApplicationRunner seed(CustomerRepository customers) {
        return args -> {
            if (customers.count() == 0) {
                NAMES.forEach(n -> customers.save(new Customer(n, n)));
            }
        };
    }
}

@Component
public class TrafficGenerator {

    private final RabbitTemplate rabbit;
    private final RestTemplate http = new RestTemplate();

    public TrafficGenerator(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @Scheduled(fixedRate = 2000)
    public void httpCall() {
        String name = DemoSeed.NAMES.get(ThreadLocalRandom.current().nextInt(DemoSeed.NAMES.size()));
        try {
            http.getForObject("http://localhost:8080/hello/" + name, String.class);
        } catch (Exception ignored) {
            // induced errors are expected demo noise
        }
    }

    @Scheduled(fixedRate = 2000)
    public void rabbitSend() {
        rabbit.convertAndSend(DemoApplication.EXCHANGE, "demo.tick", "hello");
    }
}
