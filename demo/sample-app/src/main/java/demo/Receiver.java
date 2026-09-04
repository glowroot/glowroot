package demo;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class Receiver {

    private final CustomerRepository customers;

    public Receiver(CustomerRepository customers) {
        this.customers = customers;
    }

    @RabbitListener(queues = DemoApplication.QUEUE)
    public void receive(String message) throws Exception {
        customers.findAll();
        HelloController.maybeSlowOrFail();
    }
}
