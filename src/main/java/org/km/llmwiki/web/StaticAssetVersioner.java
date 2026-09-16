package org.km.llmwiki.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Build-time static-asset URL fingerprinting (Refs #481).
 *
 * <p>Background: #479 gave static HTML/JS/CSS {@code Cache-Control: no-cache,
 * must-revalidate} plus a content {@code ETag}. Packaged-app evidence proves that
 * contract holds for every request the Browser actually sends. But browser-level
 * evidence (same persistent profile, pre-#479 JAR to post-#479 JAR on one origin)
 * proves two no-request paths survive it:
 * <ul>
 *   <li>plain navigation/reopen serves the old document and every old subresource
 *   {@code fromDiskCache} with zero requests (pre-#479 entries carry no
 *   {@code Cache-Control}, so heuristic freshness applies);</li>
 *   <li>normal reload (F5) revalidates only the top document — heuristically fresh
 *   subresources are still reused from disk without any request, so the old
 *   {@code workspace-ui.js} keeps executing and the workspace list never loads.</li>
 * </ul>
 * No server response header can reach a request the Browser never sends, so the
 * subresource URLs themselves must change when their bytes change: a new document
 * then forces a compulsory cache miss ({@code 200} with current bytes) instead of
 * a silent heuristic hit.
 *
 * <p>Contract (kept deliberately small):
 * <ul>
 *   <li>Runs once per build via {@code exec-maven-plugin} at {@code process-classes},
 *   rewriting only {@code target/classes/static/index.html}. The source tree keeps
 *   bare {@code /name.js} references; the token is always derived, never authored.</li>
 *   <li>{@code /name.js} becomes {@code /name.js?v=<12 hex chars of SHA-256 over the
 *   exact bytes served>}. Any content change therefore necessarily changes the URL;
 *   identical source always yields identical bytes (reproducible builds unaffected —
 *   the token is a pure function of file content, timestamps play no role).</li>
 *   <li>Query strings are ignored by the static resource resolution, the
 *   {@code ShallowEtagHeaderFilter} URI check ({@code getRequestURI} excludes the
 *   query), and same-origin CSP — so the versioned URL serves byte-identical
 *   content under the identical #479 cache contract. Bare URLs keep working.</li>
 * </ul>
 *
 * <p>This class is build tooling, not a runtime component: it has no Spring
 * annotations, is never injected, and exists in {@code web} only because static
 * asset serving is owned there (no new top-level package).
 */
public final class StaticAssetVersioner {

    static final int TOKEN_HEX_CHARS = 12;

    private static final Pattern ASSET_REF =
            Pattern.compile("((?:src|href)=\"/(?<path>[A-Za-z0-9._\\-/]+\\.(?:js|css)))(\\?v=[0-9a-f]*)?(?<quote>\")");

    private StaticAssetVersioner() {
    }

    /**
     * @param args single argument: the processed static directory
     *             ({@code ${project.build.outputDirectory}/static})
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || args[0].isBlank()) {
            throw new IllegalArgumentException(
                    "StaticAssetVersioner requires the static output directory as its only argument");
        }
        Path staticDir = Path.of(args[0]);
        Path index = staticDir.resolve("index.html");
        if (!Files.isRegularFile(index)) {
            throw new IllegalStateException("index.html not found under " + staticDir);
        }

        Map<String, String> tokens = new TreeMap<>();
        try (var stream = Files.list(staticDir)) {
            for (Path file : stream.sorted().toList()) {
                String name = file.getFileName().toString();
                if (Files.isRegularFile(file) && (name.endsWith(".js") || name.endsWith(".css"))) {
                    tokens.put(name, tokenFor(Files.readAllBytes(file)));
                }
            }
        }

        String html = Files.readString(index, StandardCharsets.UTF_8);
        Matcher matcher = ASSET_REF.matcher(html);
        StringBuilder rewritten = new StringBuilder(html.length() + tokens.size() * 16);
        int count = 0;
        while (matcher.find()) {
            String assetPath = matcher.group("path");
            String fileName = assetPath.contains("/")
                    ? assetPath.substring(assetPath.lastIndexOf('/') + 1)
                    : assetPath;
            Path assetFile = staticDir.resolve(assetPath);
            if (!Files.isRegularFile(assetFile)) {
                throw new IllegalStateException(
                        "index.html references missing static asset: /" + assetPath);
            }
            String token = tokens.get(fileName);
            if (token == null) {
                token = tokenFor(Files.readAllBytes(assetFile));
                tokens.put(fileName, token);
            }
            matcher.appendReplacement(
                    rewritten, Matcher.quoteReplacement(matcher.group(1) + "?v=" + token + matcher.group("quote")));
            count++;
        }
        matcher.appendTail(rewritten);
        if (count == 0) {
            throw new IllegalStateException("no versioned asset references found in " + index);
        }
        Files.writeString(index, rewritten.toString(), StandardCharsets.UTF_8);
        tokens.forEach((name, token) -> System.out.println("[asset-version] " + name + " ?v=" + token));
        System.out.println("[asset-version] rewritten " + count + " references in " + index);
    }

    static String tokenFor(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        return HexFormat.of().formatHex(digest).substring(0, TOKEN_HEX_CHARS);
    }
}
