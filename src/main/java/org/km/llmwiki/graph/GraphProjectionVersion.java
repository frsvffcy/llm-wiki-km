package org.km.llmwiki.graph;

/** Version identity for the provider-neutral graph projection contract. */
public record GraphProjectionVersion(String value) {

    public GraphProjectionVersion {
        if (value == null || value.isBlank() || !value.matches("[a-z][a-z0-9._-]{0,31}")) {
            throw new IllegalArgumentException("Graph projection version is invalid");
        }
        value = value.trim();
    }

    public static GraphProjectionVersion initial() {
        return current();
    }

    public static GraphProjectionVersion current() {
        return new GraphProjectionVersion("graph-projection-v2");
    }

    /** 僅供 migration/rejection evidence 使用；production 不得以此版本建立新 projection。 */
    public static GraphProjectionVersion legacyV1() {
        return new GraphProjectionVersion("graph-projection-v1");
    }

    /** 唯一核准的跨版本遷移；任意新字串、降版與跳版都必須 fail closed。 */
    public boolean permitsMigrationFrom(GraphProjectionVersion previous) {
        return current().equals(this) && legacyV1().equals(previous);
    }
}
