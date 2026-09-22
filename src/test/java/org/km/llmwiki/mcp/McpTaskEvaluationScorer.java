package org.km.llmwiki.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * #583 的 test-only task-level scorer。
 *
 * <p>它只評估 repository-owned task contract 與已觀察到的 tool trace / bounded outcome，
 * 不呼叫模型、不判斷自然語言真偽，也不把自己升格為 MCP 或產品 correctness authority。
 */
final class McpTaskEvaluationScorer {

    private static final Set<String> TYPED_OUTCOMES = Set.of(
            "NO_EVIDENCE",
            "RETRIEVAL_UNAVAILABLE",
            "PROVIDER_CONFIGURATION_UNAVAILABLE"
    );

    record Task(String id,
                List<String> requiredToolSequence,
                Set<String> acceptableTools,
                Set<String> forbiddenTools,
                String expectedObservable,
                boolean multiTool) {
    }

    record Observation(String taskId,
                       List<String> toolCalls,
                       String observedObservable,
                       boolean inventedUnsupportedAnswer) {
    }

    record Score(String taskId,
                 boolean requiredToolsSelected,
                 boolean requiredSequenceObserved,
                 boolean knownToolsOnly,
                 boolean acceptableToolsOnly,
                 boolean expectedObservableMatched,
                 boolean noUnsupportedInvention,
                 int requiredSelectedCount,
                 int requiredCount,
                 int invalidToolCallCount,
                 int unnecessaryToolCallCount,
                 boolean passed) {
    }

    record Metrics(double taskPassRate,
                   double requiredToolSelectionRecall,
                   double unnecessaryToolCallRate,
                   double invalidToolCallRate,
                   double multiToolCompositionPassRate,
                   double typedOutcomeInterpretationPassRate,
                   double medianToolCalls,
                   int maxToolCalls,
                   int taskCount) {
    }

    private McpTaskEvaluationScorer() {
    }

    static Score score(Task task, Observation observation) {
        if (!task.id().equals(observation.taskId())) {
            throw new IllegalArgumentException("task id mismatch");
        }
        List<String> calls = observation.toolCalls() == null
                ? List.of() : List.copyOf(observation.toolCalls());
        Set<String> callSet = new HashSet<>(calls);
        int requiredSelected = (int) task.requiredToolSequence().stream()
                .filter(callSet::contains)
                .count();
        boolean selected = requiredSelected == task.requiredToolSequence().size();
        boolean sequence = !task.multiTool()
                || appearsInOrder(task.requiredToolSequence(), calls);
        int invalid = (int) calls.stream()
                .filter(call -> !McpCapabilityManifest.isKnown(call))
                .count();
        int unnecessary = (int) calls.stream()
                .filter(call -> !task.acceptableTools().contains(call))
                .count();
        boolean forbidden = calls.stream().anyMatch(task.forbiddenTools()::contains);
        boolean outcome = task.expectedObservable().equals(observation.observedObservable());
        boolean noInvention = !observation.inventedUnsupportedAnswer();
        boolean passed = selected
                && sequence
                && invalid == 0
                && unnecessary == 0
                && !forbidden
                && outcome
                && noInvention;
        return new Score(task.id(), selected, sequence, invalid == 0,
                unnecessary == 0 && !forbidden, outcome, noInvention,
                requiredSelected, task.requiredToolSequence().size(),
                invalid, unnecessary, passed);
    }

    static Metrics summarize(List<Task> tasks, List<Observation> observations) {
        java.util.Map<String, Observation> observedById = new java.util.HashMap<>();
        observations.forEach(observation -> observedById.put(observation.taskId(), observation));

        List<Score> scores = new ArrayList<>();
        int requiredSelected = 0;
        int requiredTotal = 0;
        int calls = 0;
        int unnecessaryCalls = 0;
        int invalidCalls = 0;
        int multiTotal = 0;
        int multiPass = 0;
        int typedTotal = 0;
        int typedPass = 0;
        List<Integer> callCounts = new ArrayList<>();

        for (Task task : tasks) {
            Observation observation = observedById.getOrDefault(task.id(),
                    new Observation(task.id(), List.of(), "UNOBSERVED", false));
            Score score = score(task, observation);
            scores.add(score);
            requiredSelected += score.requiredSelectedCount();
            requiredTotal += score.requiredCount();
            int count = observation.toolCalls() == null ? 0 : observation.toolCalls().size();
            calls += count;
            callCounts.add(count);
            unnecessaryCalls += score.unnecessaryToolCallCount();
            invalidCalls += score.invalidToolCallCount();
            if (task.multiTool()) {
                multiTotal++;
                if (score.requiredSequenceObserved() && score.passed()) {
                    multiPass++;
                }
            }
            if (TYPED_OUTCOMES.contains(task.expectedObservable())) {
                typedTotal++;
                if (score.expectedObservableMatched() && score.noUnsupportedInvention()) {
                    typedPass++;
                }
            }
        }

        callCounts.sort(Comparator.naturalOrder());
        double median = median(callCounts);
        int max = callCounts.isEmpty() ? 0 : callCounts.get(callCounts.size() - 1);
        return new Metrics(
                ratio(scores.stream().filter(Score::passed).count(), tasks.size()),
                ratio(requiredSelected, requiredTotal),
                ratio(unnecessaryCalls, calls),
                ratio(invalidCalls, calls),
                ratio(multiPass, multiTotal),
                ratio(typedPass, typedTotal),
                median,
                max,
                tasks.size());
    }

    private static boolean appearsInOrder(List<String> required, List<String> calls) {
        int index = 0;
        for (String call : calls) {
            if (index < required.size() && required.get(index).equals(call)) {
                index++;
            }
        }
        return index == required.size();
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0d : (double) numerator / denominator;
    }

    private static double median(List<Integer> values) {
        if (values.isEmpty()) {
            return 0.0d;
        }
        int middle = values.size() / 2;
        if ((values.size() & 1) == 1) {
            return values.get(middle);
        }
        return (values.get(middle - 1) + values.get(middle)) / 2.0d;
    }
}
