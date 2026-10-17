package cn.ilink.service.ai;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ChatRequestBudgetTest {
    @Test void refundsOnlyKnownUsageAndKeepsUnknownReservation() {
        ChatRequestBudget budget = new ChatRequestBudget();
        try (var scope = budget.bind(); var stage = ChatRequestBudget.stage(ChatRequestBudget.Stage.REACT)) {
            try (var reservation = budget.reserve(2000, 500)) { reservation.settle(1000, 200); }
            assertEquals(1200, budget.snapshot().get("chargedTokens"));
            budget.reserve(2000, 500).close();
            assertEquals(3700, budget.snapshot().get("chargedTokens"));
            assertEquals(1, budget.snapshot().get("unknownUsageRequests"));
            assertThrows(ChatRequestBudget.BudgetStopException.class, () -> budget.reserve(10, 10));
        }
        assertNull(ChatRequestBudget.current());
    }

    @Test void exactTokenBoundaryAndStageAndToolLimits() {
        ChatRequestBudget budget = new ChatRequestBudget();
        try (var scope = budget.bind(); var stage = ChatRequestBudget.stage(ChatRequestBudget.Stage.REACT)) {
            try (var reservation = budget.reserve(15000, 1000)) { reservation.settle(15000, 1000); }
            final var exhausted = budget;
            assertThrows(ChatRequestBudget.BudgetStopException.class, () -> exhausted.reserve(0, 1));
        }
        budget = new ChatRequestBudget();
        final var reactBudget = budget;
        try (var scope = budget.bind(); var stage = ChatRequestBudget.stage(ChatRequestBudget.Stage.REACT)) {
            for (int i = 0; i < 5; i++) {
                try (var reservation = budget.reserve(1, 1)) { reservation.settle(1, 1); }
            }
            assertThrows(ChatRequestBudget.BudgetStopException.class, () -> reactBudget.reserve(1, 1));
        }
        var toolBudget = new ChatRequestBudget();
        for (int i = 0; i < 8; i++) toolBudget.beforeTool();
        assertThrows(ChatRequestBudget.BudgetStopException.class, toolBudget::beforeTool);
    }

    @Test void supplierOverrunIsRecordedAndStopsFurtherWork() {
        var budget = new ChatRequestBudget();
        try (var reservation = budget.reserve(100, 20)) {
            assertThrows(ChatRequestBudget.BudgetStopException.class, () -> reservation.settle(101, 20));
        }
        assertEquals(121, budget.snapshot().get("supplierTokensKnown"));
        assertEquals("RESERVATION_EXCEEDED", budget.snapshot().get("stopReason"));
        assertEquals(0, budget.snapshot().get("unknownUsageRequests"));
    }

    @Test void deadlineAndNestedScopesDoNotLeak() {
        AtomicLong clock = new AtomicLong();
        var budget = new ChatRequestBudget(clock::get);
        try (var outer = budget.bind()) {
            var other = new ChatRequestBudget();
            try (var inner = other.bind()) { assertSame(other, ChatRequestBudget.current()); }
            assertSame(budget, ChatRequestBudget.current());
            clock.set(45_000_000_000L);
            assertThrows(ChatRequestBudget.BudgetStopException.class, budget::beforeTool);
        }
        assertNull(ChatRequestBudget.current());
    }
}
