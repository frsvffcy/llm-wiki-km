package org.km.llmwiki.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Test-only trust contract for retrieval evaluation reports (#541).
 *
 * <p>The contract keeps experiment validity separate from retrieval quality. A score is
 * decision-grade only after the report proves that the relevant substrate was live, the feature
 * channel was actually touched, and the environment/fixture identity is current. Missing evidence
 * is reported as EVAL_REFUSED / UNOBSERVED rather than as a quality loss.
 */
final class EvaluationTrustContract {

    private EvaluationTrustContract() {
    }

    enum EvidenceStrength {
        MEASURED,
        DERIVED_PROXY,
        UNOBSERVED
    }

    record EnvironmentStamp(
            String corpusVersion,
            String policyVersion,
            List<String> enabledChannels,
            String graphProjectionVersion,
            Long graphAppliedGeneration,
            String provider,
            String model,
            String cacheIdentity,
            boolean deterministic,
            String runKind,
            String fixtureFingerprint
    ) {
        EnvironmentStamp {
            enabledChannels = List.copyOf(enabledChannels);
        }

        boolean requiresAaFloor() {
            return !deterministic;
        }
    }

    record ChannelObservation(
            String channel,
            boolean applicable,
            boolean substrateLive,
            boolean touched,
            String detail
    ) {
    }

    record Assessment(
            EnvironmentStamp environment,
            List<ChannelObservation> channels,
            EvidenceStrength evidenceStrength,
            List<String> findings
    ) {
        Assessment {
            channels = List.copyOf(channels);
            findings = List.copyOf(findings);
        }
    }

    static Assessment assess(EnvironmentStamp stamp, String expectedFingerprint,
                             List<ChannelObservation> observations) {
        List<String> findings = new java.util.ArrayList<>();
        requirePresent("corpusVersion", stamp.corpusVersion(), findings);
        requirePresent("policyVersion", stamp.policyVersion(), findings);
        requirePresent("provider", stamp.provider(), findings);
        requirePresent("model", stamp.model(), findings);
        requirePresent("cacheIdentity", stamp.cacheIdentity(), findings);
        requirePresent("runKind", stamp.runKind(), findings);
        requirePresent("fixtureFingerprint", stamp.fixtureFingerprint(), findings);
        if (stamp.enabledChannels().isEmpty()) {
            findings.add("EVAL_REFUSED environment stamp has no enabled channels");
        }
        if (expectedFingerprint == null || expectedFingerprint.isBlank()
                || !expectedFingerprint.equals(stamp.fixtureFingerprint())) {
            findings.add("EVAL_REFUSED stale/mismatched environment stamp: fixture fingerprint mismatch");
        }
        for (ChannelObservation observation : observations) {
            if (!observation.applicable()) {
                continue;
            }
            if (!observation.substrateLive()) {
                findings.add("EVAL_REFUSED " + observation.channel()
                        + ": required substrate is not live"
                        + detailSuffix(observation.detail()));
            } else if (!observation.touched()) {
                findings.add("UNOBSERVED " + observation.channel()
                        + ": channel was not touched"
                        + detailSuffix(observation.detail()));
            }
        }
        EvidenceStrength strength = findings.isEmpty()
                ? EvidenceStrength.MEASURED : EvidenceStrength.UNOBSERVED;
        return new Assessment(stamp, observations, strength, findings);
    }

    static String fingerprint(List<String> parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                String normalized = part == null ? "<null>" : part;
                digest.update(normalized.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requirePresent(String field, String value, List<String> findings) {
        if (value == null || value.isBlank()) {
            findings.add("EVAL_REFUSED environment stamp missing " + field);
        }
    }

    private static String detailSuffix(String detail) {
        return detail == null || detail.isBlank() ? "" : " (" + detail + ")";
    }
}
