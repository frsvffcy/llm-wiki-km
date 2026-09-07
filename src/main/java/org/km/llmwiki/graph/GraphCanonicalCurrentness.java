package org.km.llmwiki.graph;

import java.util.function.Supplier;

/**
 * 在 canonical authority 的交易邊界內重驗證 fingerprint，才執行短時間 control-plane CAS。
 * callback 不得存取 Graph backend；驗證失敗必須拋出 typed failure。
 */
public interface GraphCanonicalCurrentness {
    <T> T withCurrent(GraphWorkspaceScope workspace, String fingerprint, Supplier<T> action);
}
