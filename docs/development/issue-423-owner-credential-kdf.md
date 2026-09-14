# Issue #423：owner password verifier 從 unsalted SHA-256 升級為 versioned salted adaptive KDF

- 狀態：implementation（待 PR Gate + Completion Audit）
- 前置：#417（owner boundary）、#418（operations）、#422（Browser ingress contract）
- 性質：bounded credential-hardening corrective work；不建 multi-user account system

> 本文件是 #423 的 algorithm decision＋calibration＋migration contract。
> Executable authority 仍是 `web/security/` production code、tests 與 CI evidence。

## 1. Algorithm decision

候選（Java 21 runtime、dependency policy、operator ergonomics）：

| 選項 | 結論 |
| --- | --- |
| Argon2id（外部 dependency，如 argon2-jvm／Bouncy Castle） | 記憶體硬度最佳，符合 OWASP 首選；但新增 crypto 供應鏈與維護成本，且本專案 single-user＋嚴格 rate limit 下邊際效益有限 |
| PBKDF2-HMAC-SHA256（JCA／JCE，零新 dependency） | OWASP 明確列為可接受 fallback；NIST SP 800-63B-4 的 salted＋suitable KDF＋scheme／cost metadata 要求可完全滿足；標準演算法、無供應鏈新增 |

Decision：**PBKDF2-HMAC-SHA256 dependency-free baseline**，format 預留 scheme／version
欄位，未來可不經 silent reinterpretation 引入 Argon2id（unknown scheme／version
fail-fast）。不採用 raw SHA-256／SHA-512、不自製 primitive、不共用固定 salt、
不用 pepper 代替 salt／KDF。

OWASP／NIST 對照：

- OWASP Password Storage Cheat Sheet：Argon2id 首選；PBKDF2-HMAC-SHA256 在
  600,000 iterations 為核可選項（2023 調整）；每 verifier 唯一 salt；fast digest
  不得做 password storage。
- NIST SP 800-63B-4：verifier 須 salted＋suitable password hashing scheme／KDF，
  並保存 scheme／cost 資訊支援未來 migration——本 format 內建。

## 2. Calibration evidence（本機量測，非效能承諾）

JCA `PBKDF2WithHmacSHA256`、256-bit key、16-byte salt（M-series 本機，3-run 平均）：

```text
iter=60000   ≈ 30ms
iter=100000  ≈ 25ms
iter=210000  ≈ 30ms
iter=310000  ≈ 40ms
iter=600000  ≈ 80ms
single SHA-256 ≈ 0.0005ms
```

採用：default **600,000**（＝OWASP 建議值）、min **210,000**（約 fast digest 的
數萬倍，拒絕近 fast-hash 成本）、max **2,000,000**（單次登入仍 bounded；cost
由 stored verifier 攜帶，request 側不可控）。

CPU amplification 分析（#423 §E challenge）：login limiter 在 KDF 之前
（`OwnerSecurityFilter` 先 `tryAcquire(LOGIN)` 才放行到 controller 的 KDF），
5 attempts／min／IP × 80ms ≈ 平均 7ms CPU／s——無放大空間；session 並發另受
`maxSessions` 約束。`OwnerSecurityThrottleIntegrationTest` 以 executable 證明
throttle 仍在 KDF 前生效。

## 3. Format 與驗證語意

```text
pbkdf2-sha256$v1$iter=<N>$<salt-b64url-nopad>$<key-b64url-nopad>
```

16-byte `SecureRandom` salt、256-bit key；constant-time final compare
（`MessageDigest.isEqual`）；password 上限 512（與 login API 同界，超限在 KDF
前 fail-closed）；plaintext 只在 process memory 短暫存在（`PBEKeySpec`＋
`clearPassword`＋陣列抹除）。

## 4. Legacy migration contract

- `OWNER_PASSWORD_VERIFIER` 為 current authority；legacy 64-hex
  `OWNER_PASSWORD_HASH` 只作 bounded `LOCAL_ONLY` migration aid。
- 兩者並存即 startup fail-fast（不得讓弱 verifier 靜默並存）。
- `PRIVATE_INGRESS`＋legacy hash 即 startup fail-fast／readiness `NOT_READY`
 （`DeploymentProfileValidator.requireHardenedVerifier`），附 safe migration
  訊息（不回顯 secret）。
- 不把 legacy hash 包裝成新 KDF、不自動轉寫、不持久化 plaintext；rollback＝
  換回 legacy hash＋`LOCAL_ONLY`（remote 即 NOT_READY，fail-closed 方向）。

## 5. Operator 流程

```bash
java -cp <install-dir>/llm-wiki-km-*.jar \
  org.km.llmwiki.web.security.OwnerPasswordVerifierTool [--iterations N]
```

console 遮罩輸入（二次確認）、無 console 時讀 stdin；stdout 只印 verifier；
無 `--password` 選項（plaintext 永不進 CLI／history／log）。存入
`owner.env`（0600／0640）時注意 verifier 含 `$`：shell env file 必須單引號
包裹（範例檔已示範）；`DeploymentOperationsGuardTest` 鎖定範例含
`OWNER_PASSWORD_VERIFIER=` 且不提供 legacy 啟用行。

## 6. Challenge-case 對帳

1. 固定 salt＋fast hash → per-verifier `SecureRandom` salt＋PBKDF2（不同密文 test）。
2. 全系統共用 salt → 同上。
3. cost 近 fast hash → min 210,000＋`costBoundsRejectNearFastHashAndUnboundedWork`。
4. cost 由 request 控制 → cost 只讀 stored verifier（config-owned）。
5. 極大 cost 無 bound → max 2,000,000＋startup／parse fail-fast。
6. legacy 永遠 fallback → remote fail-fast＋NOT_READY，雙配置 fail-fast。
7. generator 洩漏 password → 無 `--password`、console／stdin、secret 只進 stdout verifier。
8. Browser／diagnostic 回傳 credential metadata → login response 不變（token＋timeouts）；contract tests 斷言無洩漏。
9. limiter 在 KDF 後 → filter 順序 limiter→KDF＋throttle integration＋§2 分析。
10. 順手建 multi-user schema → guard（無 tenant／role 語彙）＋diff 無 schema。
