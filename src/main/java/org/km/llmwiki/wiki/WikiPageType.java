package org.km.llmwiki.wiki;

/**
 * Controlled vocabulary for Wiki page types.
 *
 * <p>Each value maps to a deterministic vault sub-directory.
 * Callers must always use {@link #from(String)} to parse an external string
 * so that unknown values are rejected instead of silently defaulting.
 *
 * <p>Vault layout:
 * <pre>
 *   vault/
 *   ├── concepts/
 *   ├── technologies/
 *   ├── troubleshooting/
 *   ├── decisions/
 *   ├── projects/
 *   ├── references/
 *   ├── howtos/
 *   ├── people/
 *   └── organizations/
 * </pre>
 */
public enum WikiPageType {

    CONCEPT("concepts"),
    TECHNOLOGY("technologies"),
    TROUBLESHOOTING("troubleshooting"),
    DECISION("decisions"),
    PROJECT("projects"),
    REFERENCE("references"),
    HOWTO("howtos"),
    PERSON("people"),
    ORGANIZATION("organizations");

    private final String folderName;

    WikiPageType(String folderName) {
        this.folderName = folderName;
    }

    /**
     * Returns the vault sub-directory name for this page type.
     * The returned value is a single path segment (no slashes).
     */
    public String folderName() {
        return folderName;
    }

    /**
     * Parses a raw string to a {@code WikiPageType}.
     *
     * @param rawType the string to parse (case-insensitive, trimmed)
     * @return the matching enum value
     * @throws WikiPathValidationException if {@code rawType} is null, blank, or not a known type
     */
    public static WikiPageType from(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            throw new WikiPathValidationException(
                    WikiPathValidationException.Reason.UNKNOWN_PAGE_TYPE,
                    "Wiki page type must not be null or blank");
        }
        String normalized = rawType.strip().toUpperCase();
        for (WikiPageType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        throw new WikiPathValidationException(
                WikiPathValidationException.Reason.UNKNOWN_PAGE_TYPE,
                "Unknown Wiki page type: '" + rawType + "'. Accepted values: " + acceptedValues());
    }

    /**
     * Finds a {@code WikiPageType} matching the given vault folder name.
     *
     * @param folderName the folder name (e.g. {@code "concepts"})
     * @return the matching enum value
     * @throws WikiPathValidationException if the folder name does not match any controlled type
     */
    public static WikiPageType fromFolderName(String folderName) {
        if (folderName == null || folderName.isBlank()) {
            throw new WikiPathValidationException(
                    WikiPathValidationException.Reason.UNKNOWN_PAGE_TYPE,
                    "Vault folder name must not be null or blank");
        }
        String normalized = folderName.strip().toLowerCase();
        for (WikiPageType type : values()) {
            if (type.folderName().equals(normalized)) {
                return type;
            }
        }
        throw new WikiPathValidationException(
                WikiPathValidationException.Reason.UNKNOWN_PAGE_TYPE,
                "Unknown vault folder name: '" + folderName + "'");
    }

    private static String acceptedValues() {
        StringBuilder sb = new StringBuilder();
        WikiPageType[] values = values();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values[i].name());
        }
        return sb.toString();
    }
}
