package org.km.llmwiki.testsupport;

import org.km.llmwiki.graph.GraphCanonicalCurrentness;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import java.util.function.Supplier;

/** 僅供低階 adapter/lifecycle fixture；production ingress 測試必須使用真實 canonical guard。 */
public final class AssumedCurrentGraphFixture implements GraphCanonicalCurrentness {
    @Override
    public <T> T withCurrent(GraphWorkspaceScope workspace, String fingerprint, Supplier<T> action) {
        return action.get();
    }
}
