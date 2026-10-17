package cn.ilink.service.ai;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Request-local accounting. Unknown paid usage stops further dispatch instead of becoming zero. */
public final class ChatRequestBudget {
    public enum Stage { PLAN, REACT, GENERATE, VERIFY, REWRITE, EXTRA }

    public static final class BudgetStopException extends RuntimeException {
        public BudgetStopException(String reason) { super(reason); }
    }

    private static final ThreadLocal<ChatRequestBudget> CURRENT = new ThreadLocal<>();
    private final EnumMap<Stage, Integer> calls = new EnumMap<>(Stage.class);
    private final List<Map<String, Object>> usageRecords = new ArrayList<>();
    private final LongSupplier nanoClock;
    private final long deadline;
    private Stage stage = Stage.EXTRA;
    private int requests, tools, charged, actual, unknown;
    private String stopped;

    public ChatRequestBudget() { this(System::nanoTime); }

    ChatRequestBudget(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        deadline = nanoClock.getAsLong() + 45_000_000_000L;
    }

    public interface Scope extends AutoCloseable { @Override void close(); }

    public Scope bind() {
        ChatRequestBudget previous = CURRENT.get();
        CURRENT.set(this);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }

    public static ChatRequestBudget current() { return CURRENT.get(); }

    public int completionLimit(int requested) {
        int limit = switch (stage) {
            case PLAN -> 384;
            case REACT -> 256;
            case GENERATE, REWRITE -> 1200;
            case VERIFY, EXTRA -> VERDICT_COMPLETION_LIMIT;
        };
        return Math.min(requested, limit);
    }

    /**
     * Completion cap for one semantic verdict.
     *
     * <p>Raised from 512 to 768 because the v10 M10 sample returned {@code finishReason=length} on
     * BOTH verdict stages while its evidence packet was only 2,191 of 3,000 tokens: the verdict
     * contract, not the evidence, was the binding constraint. The verdict contract was compressed
     * in the same change, so this cap and that contract must be tuned together; raising the cap
     * alone would only hide truncation again.
     */
    public static final int VERDICT_COMPLETION_LIMIT = 768;

    public static Scope stage(Stage stage) {
        ChatRequestBudget budget = current();
        if (budget == null) return () -> {};
        Stage previous = budget.stage;
        budget.stage = stage;
        return () -> budget.stage = previous;
    }

    public int remainingMillis() {
        check();
        return (int) Math.max(1, (deadline - nanoClock.getAsLong()) / 1_000_000);
    }

    public void check() {
        if (stopped != null) throw new BudgetStopException(stopped);
        if (nanoClock.getAsLong() >= deadline) stop("DEADLINE");
    }

    private void stop(String reason) {
        stopped = reason;
        throw new BudgetStopException(reason);
    }

    public void beforeTool() {
        check();
        if (tools >= 8) stop("TOOL_LIMIT");
        tools++;
    }

    public Reservation reserve(int promptBound, int outputLimit) {
        check();
        if (promptBound < 0 || outputLimit < 1) throw new IllegalArgumentException("Invalid reservation");
        if (requests >= 10) stop("MODEL_LIMIT");
        int stageLimit = stage == Stage.REACT ? 5 : 1;
        if (calls.getOrDefault(stage, 0) >= stageLimit) stop("STAGE_LIMIT_" + stage);
        long amount = (long) promptBound + outputLimit;
        if (amount > 16_000 - charged) stop("TOKEN_RESERVATION_LIMIT");
        requests++;
        calls.merge(stage, 1, Integer::sum);
        charged += (int) amount;
        return new Reservation((int) amount, promptBound, outputLimit);
    }

    public final class Reservation implements AutoCloseable {
        private final int amount, promptBound, outputLimit;
        private boolean settled;
        private final Map<String, Object> record = new LinkedHashMap<>();
        private final long started = nanoClock.getAsLong();

        private Reservation(int amount, int promptBound, int outputLimit) {
            this.amount = amount; this.promptBound = promptBound; this.outputLimit = outputLimit;
            record.put("stage", stage.name());
            record.put("promptReservation", promptBound);
            record.put("completionLimit", outputLimit);
            record.put("usageStatus", "PENDING");
            usageRecords.add(record);
        }

        public void settle(Integer prompt, Integer completion) {
            if (settled) throw new IllegalStateException("Usage already settled");
            if (prompt == null || completion == null || prompt < 0 || completion < 0) {
                close();
                stop("USAGE_UNKNOWN");
            }
            settled = true;
            record.put("promptTokens", prompt);
            record.put("completionTokens", completion);
            record.put("usageStatus", "KNOWN");
            record.put("elapsedMs", (nanoClock.getAsLong() - started) / 1_000_000);
            long total = (long) prompt + completion;
            actual = (int) Math.min(Integer.MAX_VALUE, actual + total);
            charged = (int) Math.min(Integer.MAX_VALUE, charged - amount + total);
            if (prompt > promptBound || completion > outputLimit || charged > 16_000)
                stop("RESERVATION_EXCEEDED");
            check();
        }

        @Override public void close() {
            if (!settled) {
                settled = true;
                unknown++;
                record.put("usageStatus", "UNKNOWN");
                record.put("elapsedMs", (nanoClock.getAsLong() - started) / 1_000_000);
                stopped = "USAGE_UNKNOWN";
                // Keep the full reservation charged. A transport failure is not proof of zero usage.
            }
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("usageRecords", usageRecords.stream().map(Map::copyOf).toList());
        result.put("modelRequests", requests);
        result.put("toolExecutions", tools);
        result.put("stageRequests", new EnumMap<>(calls));
        result.put("supplierTokensKnown", actual);
        result.put("chargedTokens", charged);
        result.put("unknownUsageRequests", unknown);
        result.put("stopReason", stopped == null ? "" : stopped);
        return result;
    }
}
