package com.tailoredbrands.otd.emsbroker.generator;

import com.tailoredbrands.otd.emsbroker.catalog.Catalog;
import com.tailoredbrands.otd.emsbroker.model.LegacyOrder;
import com.tailoredbrands.otd.emsbroker.model.LegacyOrder.LegacyAlteration;
import com.tailoredbrands.otd.emsbroker.model.LegacyOrder.LegacyOrderLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates realistic legacy orders from the shared catalog.
 *
 * <ul>
 *   <li>Order type weights from {@code catalog.json} (R 50 / T 25 / X 15 / E 7 / C 3).</li>
 *   <li>R: 1-3 take-with lines (suits, shirts, trousers, shoes, accessories).</li>
 *   <li>T: a suit plus 1-2 alteration work-order lines (HEM / SLEEVE / WAIST / TAPER) for the store's tailor shop.</li>
 *   <li>X: 1-3 rental lines and an event date 2-10 weeks out.</li>
 *   <li>E: 1-3 ship lines attributed to the e-commerce "store" 9001.</li>
 *   <li>C: one made-to-measure line, shipped from the vendor.</li>
 * </ul>
 *
 * Thread-safe; deterministic when constructed with a seed.
 */
public class LegacyOrderGenerator {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    private final Catalog catalog;
    private final Random random;
    private final Clock clock;
    private final List<String> weightedTypes;
    private final Map<String, AtomicInteger> txnCounters = new ConcurrentHashMap<>();

    public LegacyOrderGenerator(Catalog catalog) {
        this(catalog, new Random(), Clock.systemDefaultZone());
    }

    public LegacyOrderGenerator(Catalog catalog, long seed) {
        this(catalog, new Random(seed), Clock.systemDefaultZone());
    }

    public LegacyOrderGenerator(Catalog catalog, Random random, Clock clock) {
        this.catalog = catalog;
        this.random = random;
        this.clock = clock;
        this.weightedTypes = expandWeights(catalog.orderTypeWeights());
    }

    public LegacyOrder next() {
        return next(randomType(), null);
    }

    /** An order type drawn from the configured weights. */
    public String randomType() {
        synchronized (random) {
            return weightedTypes.get(random.nextInt(weightedTypes.size()));
        }
    }

    public LegacyOrder next(String orderType, String storeNbr) {
        synchronized (random) {
            Catalog.Store store = storeNbr == null ? pick(catalog.stores()) : catalog.store(storeNbr);
            String effectiveStore = "E".equals(orderType) ? catalog.ecomStoreNbr() : store.storeNbr();
            LocalDate today = LocalDate.now(clock);
            String orderNbr = nextOrderNbr(effectiveStore, today);
            String custNbr = customerNumber(orderType);
            List<LegacyOrderLine> lines = switch (orderType) {
                case "T" -> tailoredLines(store);
                case "X" -> rentalLines();
                case "E" -> ecomLines();
                case "C" -> customLines();
                case "R" -> retailLines();
                default -> throw new IllegalArgumentException("Unknown order type " + orderType);
            };
            LocalDate eventDate = "X".equals(orderType)
                    ? today.plusWeeks(2 + random.nextInt(9)).plusDays(random.nextInt(7))
                    : null;
            return new LegacyOrder(orderNbr, orderType, effectiveStore, custNbr, today, eventDate, "NEW", lines);
        }
    }

    private String nextOrderNbr(String storeNbr, LocalDate date) {
        AtomicInteger counter = txnCounters.computeIfAbsent(storeNbr, s -> new AtomicInteger(100 + random.nextInt(900)));
        int txn = counter.incrementAndGet();
        return storeNbr + "-" + YYMMDD.format(date) + "-" + String.format("%06d", txn);
    }

    private String customerNumber(String orderType) {
        // Walk-in retail customers often have no loyalty number; everything else is always tied to a customer.
        if ("R".equals(orderType) && random.nextInt(100) < 30) {
            return null;
        }
        return "C-" + (10000 + random.nextInt(90000));
    }

    private List<LegacyOrderLine> retailLines() {
        List<Catalog.Sku> pool = catalog.skusInCategories("SUIT", "SPORTCOAT", "TROUSER", "SHIRT", "ACCESSORY", "SHOES", "OUTERWEAR");
        int count = 1 + random.nextInt(3);
        List<LegacyOrderLine> lines = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Catalog.Sku sku = pick(pool);
            int qty = random.nextInt(10) < 8 ? 1 : 2;
            lines.add(new LegacyOrderLine(i, sku.sku(), qty, sku.price(), "P", null));
        }
        return lines;
    }

    private List<LegacyOrderLine> tailoredLines(Catalog.Store store) {
        List<LegacyOrderLine> lines = new ArrayList<>();
        Catalog.Sku suit = pick(catalog.skusInCategory("SUIT"));
        lines.add(new LegacyOrderLine(1, suit.sku(), 1, suit.price(), "P", null));

        List<Catalog.AlterationService> services = new ArrayList<>(catalog.alterations());
        int count = 1 + random.nextInt(2);
        for (int i = 0; i < count && !services.isEmpty(); i++) {
            Catalog.AlterationService service = services.remove(random.nextInt(services.size()));
            Catalog.Sku altSku = catalog.sku(service.sku());
            BigDecimal inches = BigDecimal.valueOf(
                    Math.round((service.minInches() + random.nextDouble() * (service.maxInches() - service.minInches())) * 2) / 2.0)
                    .setScale(1, RoundingMode.HALF_UP);
            lines.add(new LegacyOrderLine(lines.size() + 1, altSku.sku(), 1, altSku.price(), "A",
                    new LegacyAlteration(service.type(), inches, store.tailorShopNbr())));
        }
        return lines;
    }

    private List<LegacyOrderLine> rentalLines() {
        List<Catalog.Sku> pool = new ArrayList<>(catalog.skusInCategory("RENTAL"));
        int count = 1 + random.nextInt(3);
        List<LegacyOrderLine> lines = new ArrayList<>();
        for (int i = 1; i <= count && !pool.isEmpty(); i++) {
            Catalog.Sku sku = pool.remove(random.nextInt(pool.size()));
            lines.add(new LegacyOrderLine(i, sku.sku(), 1, sku.price(), "P", null));
        }
        return lines;
    }

    private List<LegacyOrderLine> ecomLines() {
        List<Catalog.Sku> pool = catalog.skusInCategories("SHIRT", "ACCESSORY", "OUTERWEAR", "TROUSER", "SHOES");
        int count = 1 + random.nextInt(3);
        List<LegacyOrderLine> lines = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Catalog.Sku sku = pick(pool);
            lines.add(new LegacyOrderLine(i, sku.sku(), 1, sku.price(), "S", null));
        }
        return lines;
    }

    private List<LegacyOrderLine> customLines() {
        Catalog.Sku sku = pick(catalog.skusInCategory("CUSTOM"));
        return List.of(new LegacyOrderLine(1, sku.sku(), 1, sku.price(), "S", null));
    }

    private <T> T pick(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }

    private static List<String> expandWeights(Map<String, Integer> weights) {
        List<String> expanded = new ArrayList<>();
        weights.forEach((type, weight) -> {
            for (int i = 0; i < weight; i++) {
                expanded.add(type);
            }
        });
        if (expanded.isEmpty()) {
            throw new IllegalArgumentException("orderTypeWeights must not be empty");
        }
        return List.copyOf(expanded);
    }
}
