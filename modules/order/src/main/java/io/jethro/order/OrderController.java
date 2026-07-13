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

    /** One page of a day's blotter (server zone). */
    public record OrderPage(long total, int page, int size, int totalPages,
                            List<OrderRepository.OrderRow> orders) {
    }

    /** Day-scoped, paginated blotter: /api/orders/day?date=YYYY-MM-DD&page=0&size=100. */
    @GetMapping("/api/orders/day")
    public OrderPage ordersForDay(
            @org.springframework.web.bind.annotation.RequestParam(name = "date", required = false) String date,
            @org.springframework.web.bind.annotation.RequestParam(name = "page", defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(name = "size", defaultValue = "100") int size) {
        java.time.LocalDate day = date == null || date.isBlank()
                ? java.time.LocalDate.now() : java.time.LocalDate.parse(date);
        int pageSize = Math.min(Math.max(size, 1), 500);
        int pageIndex = Math.max(page, 0);
        long total = repository.countOrdersForDay(day);
        int totalPages = (int) Math.max(1, (total + pageSize - 1) / pageSize);
        return new OrderPage(total, pageIndex, pageSize, totalPages,
                repository.ordersForDay(day, pageIndex * pageSize, pageSize));
    }

    @GetMapping("/api/fills")
    public List<OrderRepository.FillRow> fills() {
        return repository.recentFills(100);
    }
}
