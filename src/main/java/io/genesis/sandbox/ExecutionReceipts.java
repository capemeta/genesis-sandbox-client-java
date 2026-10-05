package io.genesis.sandbox;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import static io.genesis.sandbox.SandboxModels.*;

final class ExecutionReceipts {
    private ExecutionReceipts() {}

    static void validateStatus(String status) {
        if (!Set.of("queued", "running", "succeeded", "failed", "cancelled", "timed_out", "interrupted").contains(status)) {
            throw new IllegalArgumentException("Invalid execution status");
        }
    }

    static ExecRecord record(ExecRecord record, String session, String execution, String operation) throws IOException {
        if (record == null || record.execId == null || record.execId.isBlank() || record.operationId == null || record.operationId.isBlank()
                || !session.equals(record.sessionId) || (execution != null && !execution.equals(record.execId))
                || (operation != null && !operation.equals(record.operationId))) {
            throw new IOException("Execution receipt original identity mismatch");
        }
        try { validateStatus(record.status); }
        catch (IllegalArgumentException | NullPointerException error) { throw new IOException("Invalid execution receipt status", error); }
        // 停止只读取显式布尔事实，不由终态或时间戳推导。
        if (Boolean.TRUE.equals(record.stopConfirmed) && !record.terminal()) throw new IOException("Nonterminal execution cannot confirm physical stop");
        return record;
    }

    static ExecRecordList page(ExecRecordList page, String session, Integer requestedLimit) throws IOException {
        int limit = requestedLimit == null ? 50 : requestedLimit;
        if (page == null || page.items == null || page.items.size() > limit || page.total < page.items.size()) throw new IOException("Invalid execution history page");
        Set<String> seen = new HashSet<>();
        for (ExecRecord item : page.items) {
            record(item, session, null, null);
            if (!seen.add(item.execId)) throw new IOException("Duplicate execution history identity");
        }
        return page;
    }
}
