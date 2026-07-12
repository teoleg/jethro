package io.jethro.order;

import io.jethro.domain.OrderType;
import io.jethro.domain.Side;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Order REST surface: submit an order and read the blotter/fills (registered by the app). */
@RestController
public class OrderController {

    /** Submit payload; decimals as strings (invariant 1). limitPrice/idempotencyKey optional. */
    public record OrderRequest(String bookId, String instrumentId, String side, String type,
                               String quantity, String limitPrice, String idempotencyKey) {
    }

    private final OrderService orderService;
    private final OrderRepository repository;

    public OrderController(OrderService orderService, OrderRepository repository) {
        this.orderService = orderService;
        this.repository = repository;
    }

    @PostMapping("/api/orders")
    public ResponseEntity<?> submit(@RequestBody OrderRequest request) {
        try {
            var command = new NewOrder(
                    request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                            ? "idem-" + UUID.randomUUID() : request.idempotencyKey(),
                    request.bookId(),
                    request.instrumentId(),
                    Side.valueOf(request.side().toUpperCase()),
                    request.type() == null ? OrderType.MARKET : OrderType.valueOf(request.type().toUpperCase()),
                    new BigDecimal(request.quantity()),
                    request.limitPrice() == null || request.limitPrice().isBlank()
                            ? null : new BigDecimal(request.limitPrice()));
            var order = orderService.submit(command);
            // Return the persisted row view (single-element lookup by re-reading recent).
            var row = repository.recentOrders(50).stream()
                    .filter(o -> o.orderId().equals(order.orderId()))
                    .findFirst().orElseThrow();
            return ResponseEntity.ok(row);
        } catch (IllegalArgumentException | ArithmeticException e) {
            // Bad enum, unparseable/negative/over-precise decimal — client error, not a 500.
            return ResponseEntity.badRequest().body(new ApiError(e.getMessage()));
        }
    }

    /** Error payload so a rejected submit shows a reason in the UI, not a bare 500. */
    public record ApiError(String error) {
    }

    @GetMapping("/api/orders")
    public List<OrderRepository.OrderRow> orders() {
        return repository.recentOrders(100);
    }

    @GetMapping("/api/fills")
    public List<OrderRepository.FillRow> fills() {
        return repository.recentFills(100);
    }
}
