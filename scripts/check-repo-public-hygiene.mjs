#!/usr/bin/env node
/**
 * Repository public-content hygiene gate (Refs #578).
 *
 * Scans only Git-public surfaces. It never walks private workspace roots outside
 * the repository and never prints matched values or raw source lines.
 */
import { execFile } from "node:child_process";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";
import { pathToFileURL } from "node:url";

const execFileAsync = promisify(execFile);
const MAX_TEXT_BYTES = 4 * 1024 * 1024;

const TEXT_EXTENSIONS = new Set([
  ".bash", ".bpmn", ".cjs", ".conf", ".css", ".csv", ".gradle", ".html",
  ".ini", ".java", ".js", ".json", ".kt", ".kts", ".md", ".mjs", ".properties",
  ".sh", ".sql", ".svg", ".toml", ".tsv", ".txt", ".xml", ".yaml", ".yml", ".zsh"
]);
const TEXT_BASENAMES = new Set([
  ".editorconfig", ".gitattributes", ".gitignore", "Dockerfile", "Makefile", "mvnw"
]);

export const SHARED_CREDENTIAL_KEYS = Object.freeze([
  "OPENAI_API_KEY",
  "EMBEDDING_PROVIDER_API_KEY",
  "QUERY_REWRITE_PROVIDER_API_KEY",
  "MCP_ADAPTER_AUTH_TOKEN",
  "OWNER_PASSWORD",
  "OWNER_PASSWORD_HASH",
  "OWNER_PASSWORD_VERIFIER"
]);

const FORBIDDEN_ROOTS = new Set(["archive", "data", "graph", "logs", "temp", "vault"]);
const FORBIDDEN_CREDENTIAL_FILES = new Set([
  "credentials.json", "service-account.json", "id_rsa", "id_ed25519", "owner.env"
]);
const SAFE_ENV_FILE = /^\.env\.(?:example|sample|template)(?:\..+)?$/iu;
const ENV_FILE = /^\.env(?:\..+)?$/iu;
const DB_OR_RUNTIME_SUFFIX = /(?:\.db(?:-wal|-shm)?|\.sqlite3?|\.log)$/iu;

const PRIVATE_KEY_PATTERN = /-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----/u;
const CREDENTIAL_SHAPE_PATTERNS = Object.freeze([
  /\bgh[pousr]_[A-Za-z0-9]{20,}\b/u,
  /\bsk-[A-Za-z0-9_-]{20,}\b/u,
  /\bxox[bap]-[A-Za-z0-9-]{20,}\b/u,
  /\bAKIA[0-9A-Z]{16}\b/u,
  /\bAIza[0-9A-Za-z_-]{30,}\b/u,
  /\bBearer[ \t]+[A-Za-z0-9][A-Za-z0-9._~+/-]{19,}={0,2}\b/u
]);

const MAC_HOME_PATTERN = /\/Users\/([^/\s"'\x60<>]+)(?:\/[^\s"'\x60<>]*)?/gu;
const LINUX_HOME_PATTERN = /\/home\/([^/\s"'\x60<>]+)(?:\/[^\s"'\x60<>]*)?/gu;
const WINDOWS_HOME_PATTERN = /\b[A-Za-z]:\\Users\\([^\\\s"'\x60<>]+)(?:\\[^\s"'\x60<>]*)?/gu;
const SAFE_HOME_NAMES = new Set(["demo", "example", "runner", "user", "you"]);

const GENERIC_SECRET_ASSIGNMENT =
  /\b(password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token|bearer[_-]?token)\b\s*[:=]\s*(?:"([^"]*)"|'([^']*)'|([^\s#;,]+))/giu;

function escapeRegex(value) {
  return value.replace(new RegExp("[.*+?^$(){}|\\[\\]\\\\]", "gu"), "\\$&");
}

const KNOWN_ASSIGNMENT_PATTERNS = SHARED_CREDENTIAL_KEYS.map(function (key) {
  return {
    key,
    pattern: new RegExp(
      "\\b(" + escapeRegex(key) + ")\\b\\s*[:=]\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s#;,]+))",
      "giu"
    )
  };
});

function normalizedValue(raw) {
  return String(raw == null ? "" : raw).trim().replace(/^["']|["']$/gu, "");
}

export function isSafePlaceholder(raw) {
  const value = normalizedValue(raw);
  if (value === "") return true;
  if (/^<[^<>]+>$/u.test(value)) return true;
  if (/^\$\{[A-Z0-9_]+(?::[^}]*)?\}$/u.test(value)) return true;
  if (/^\$[A-Z][A-Z0-9_]*$/u.test(value)) return true;
  if (/^(?:YOUR_|EXAMPLE_|PLACEHOLDER_|DUMMY_|TEST_|FAKE_|REDACTED_)/iu.test(value)) return true;
  if (/^(?:changeme|change-me|example|dummy|test|fake|redacted|not-a-real[-_a-z0-9]*|x{4,}|\*{4,})$/iu.test(value)) return true;
  if (value.includes("...")) return true;
  return false;
}

function looksHighEntropyLiteral(raw) {
  const value = normalizedValue(raw);
  if (value.length < 20 || isSafePlaceholder(value) || /\s/u.test(value)) return false;
  const classes = [
    /[a-z]/u.test(value),
    /[A-Z]/u.test(value),
    /[0-9]/u.test(value),
    /[^A-Za-z0-9]/u.test(value)
  ].filter(Boolean).length;
  return classes >= 3 || (value.length >= 32 && classes >= 2);
}

function finding(category, relativePath, line) {
  return Object.freeze({
    severity: "ERROR",
    category,
    path: relativePath,
    line: line == null ? null : line
  });
}

export function inspectPath(relativePath) {
  const normalized = String(relativePath).replaceAll("\\", "/").replace(/^\.\//u, "");
  const parts = normalized.split("/").filter(Boolean);
  const basename = parts.length === 0 ? "" : parts[parts.length - 1];

  if (parts.length > 1 && FORBIDDEN_ROOTS.has(parts[0])) {
    return finding("PRIVATE_RUNTIME_ARTIFACT", normalized);
  }
  if (ENV_FILE.test(basename) && !SAFE_ENV_FILE.test(basename)) {
    return finding("ENV_FILE", normalized);
  }
  if (DB_OR_RUNTIME_SUFFIX.test(basename)) {
    return finding("PRIVATE_RUNTIME_ARTIFACT", normalized);
  }
  if (FORBIDDEN_CREDENTIAL_FILES.has(basename.toLowerCase())) {
    return finding("CREDENTIAL_FILE", normalized);
  }
  return null;
}

export function isTextPath(relativePath) {
  const basename = path.posix.basename(String(relativePath).replaceAll("\\", "/"));
  return TEXT_BASENAMES.has(basename)
    || TEXT_EXTENSIONS.has(path.posix.extname(basename).toLowerCase());
}

function valueFromAssignmentMatch(match) {
  return match[2] == null ? (match[3] == null ? (match[4] == null ? "" : match[4]) : match[3]) : match[2];
}

function unsafeHomePath(line) {
  for (const pattern of [MAC_HOME_PATTERN, LINUX_HOME_PATTERN, WINDOWS_HOME_PATTERN]) {
    pattern.lastIndex = 0;
    let match;
    while ((match = pattern.exec(line)) !== null) {
      const home = String(match[1] == null ? "" : match[1]).toLowerCase();
      if (!SAFE_HOME_NAMES.has(home)) return true;
    }
  }
  return false;
}

export function scanText(relativePath, text) {
  const findings = [];
  const lines = String(text).split(/\r?\n/u);

  lines.forEach(function (line, index) {
    const lineNo = index + 1;

    if (PRIVATE_KEY_PATTERN.test(line)) {
      findings.push(finding("PRIVATE_KEY_BLOCK", relativePath, lineNo));
    }

    if (CREDENTIAL_SHAPE_PATTERNS.some(function (pattern) { return pattern.test(line); })) {
      findings.push(finding("CREDENTIAL_SHAPE", relativePath, lineNo));
    }

    for (const entry of KNOWN_ASSIGNMENT_PATTERNS) {
      entry.pattern.lastIndex = 0;
      let match;
      while ((match = entry.pattern.exec(line)) !== null) {
        const value = valueFromAssignmentMatch(match);
        if (!isSafePlaceholder(value) && normalizedValue(value).length >= 8) {
          findings.push(finding("ASSIGNED_CREDENTIAL", relativePath, lineNo));
          break;
        }
      }
    }

    GENERIC_SECRET_ASSIGNMENT.lastIndex = 0;
    let genericMatch;
    while ((genericMatch = GENERIC_SECRET_ASSIGNMENT.exec(line)) !== null) {
      const value = valueFromAssignmentMatch(genericMatch);
      if (looksHighEntropyLiteral(value)) {
        findings.push(finding("HARDCODED_SECRET", relativePath, lineNo));
        break;
      }
    }

    if (unsafeHomePath(line)) {
      findings.push(finding("DEVELOPER_ABSOLUTE_PATH", relativePath, lineNo));
    }
  });

  const unique = new Map();
  for (const item of findings) {
    const key = item.category + "|" + item.path + "|" + (item.line == null ? "" : item.line);
    unique.set(key, item);
  }
  return Array.from(unique.values());
}

function parseNulList(output) {
  return String(output).split("\0").filter(Boolean);
}

async function gitText(root, args) {
  const result = await execFileAsync("git", args, {
    cwd: root,
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024
  });
  return result.stdout;
}

async function gitBuffer(root, args) {
  const result = await execFileAsync("git", args, {
    cwd: root,
    encoding: "buffer",
    maxBuffer: MAX_TEXT_BYTES + 1024
  });
  return result.stdout;
}

export async function collectPaths(options) {
  const mode = options.mode;
  const root = options.root;
  const base = options.base;
  const head = options.head || "HEAD";

  if (mode === "tracked") {
    return parseNulList(await gitText(root, ["ls-files", "-z"]));
  }
  if (mode === "staged") {
    return parseNulList(await gitText(
      root, ["diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z", "--"]
    ));
  }
  if (mode === "changed") {
    if (!base) throw new Error("BASE_REQUIRED");
    return parseNulList(await gitText(
      root, ["diff", "--name-only", "--diff-filter=ACMR", "-z", base + "..." + head, "--"]
    ));
  }
  throw new Error("INVALID_MODE");
}

async function readCandidate(root, relativePath, mode) {
  if (mode === "staged") {
    return gitBuffer(root, ["show", ":" + relativePath]);
  }
  return readFile(path.join(root, relativePath));
}

export async function scanRepository(options = {}) {
  const mode = options.mode || "tracked";
  const root = path.resolve(options.root || process.cwd());
  const base = options.base;
  const head = options.head || "HEAD";
  const paths = await collectPaths({ mode, root, base, head });
  const findings = [];

  for (const relativePath of paths) {
    const pathIssue = inspectPath(relativePath);
    if (pathIssue) findings.push(pathIssue);
    if (!isTextPath(relativePath)) continue;

    let buffer;
    try {
      buffer = await readCandidate(root, relativePath, mode);
    } catch {
      findings.push(finding("UNREADABLE_PUBLIC_FILE", relativePath));
      continue;
    }

    if (buffer.length > MAX_TEXT_BYTES) {
      findings.push(finding("OVERSIZED_TEXT_FILE", relativePath));
      continue;
    }
    if (buffer.includes(0)) {
      findings.push(finding("BINARY_CONTENT_IN_TEXT_FILE", relativePath));
      continue;
    }
    findings.push(...scanText(relativePath, buffer.toString("utf8")));
  }

  return { mode, scannedFiles: paths.length, findings };
}

function safeLogPath(value) {
  return String(value).replace(/[\u0000-\u001f\u007f]/gu, "?");
}

export function formatFinding(item) {
  const location = item.line == null
    ? safeLogPath(item.path)
    : safeLogPath(item.path) + ":" + item.line;
  return "[repo-hygiene] " + item.severity + " " + item.category + " " + location;
}

function parseArgs(argv) {
  const options = { mode: "tracked", root: process.cwd(), head: "HEAD" };
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (token === "--mode") options.mode = argv[++index];
    else if (token === "--root") options.root = argv[++index];
    else if (token === "--base") options.base = argv[++index];
    else if (token === "--head") options.head = argv[++index];
    else throw new Error("INVALID_ARGUMENT");
  }
  return options;
}

export async function runCli(argv = process.argv.slice(2)) {
  try {
    const options = parseArgs(argv);
    const result = await scanRepository(options);
    if (result.findings.length > 0) {
      for (const item of result.findings) console.error(formatFinding(item));
      console.error(
        "[repo-hygiene] FAIL: " + result.findings.length
        + " finding(s) in " + result.scannedFiles + " public file(s)"
      );
      return 1;
    }
    console.log(
      "[repo-hygiene] PASS: " + result.mode + " scan checked "
      + result.scannedFiles + " public file(s)"
    );
    return 0;
  } catch (error) {
    const code = error instanceof Error && /^[A-Z_]+$/u.test(error.message)
      ? error.message : "SCANNER_OPERATION_FAILED";
    console.error("[repo-hygiene] FAIL: " + code);
    return 1;
  }
}

const invokedPath = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : "";
if (import.meta.url === invokedPath) {
  process.exitCode = await runCli();
}
