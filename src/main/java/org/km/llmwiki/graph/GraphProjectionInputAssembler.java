package org.km.llmwiki.graph;

/** 從受信任的 workspace authority 讀取目前 canonical projection；不得寫入投影。 */
@FunctionalInterface
public interface GraphProjectionInputAssembler {
    GraphProjectionInput assemble(GraphWorkspaceScope workspace);
}
