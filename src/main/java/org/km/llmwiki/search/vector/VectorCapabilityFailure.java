package org.km.llmwiki.search.vector;

/** Stable failure taxonomy; implementation-specific exceptions stay behind the adapter. */
public enum VectorCapabilityFailure {
    NONE,
    DISABLED,
    EXTENSION_PATH_MISSING,
    EXTENSION_PATH_INVALID,
    EXTENSION_LOAD_FAILED,
    INCOMPATIBLE_RUNTIME,
    UNSUPPORTED_ENCODING,
    INVALID_DIMENSION,
    CAPABILITY_CHECK_FAILED
}
