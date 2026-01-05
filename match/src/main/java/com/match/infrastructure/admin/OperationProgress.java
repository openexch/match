package com.match.infrastructure.admin;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks operation progress for admin operations (rolling-update, snapshot, compact).
 * Uses immutable state pattern for thread-safe reads.
 * Progress is available via REST API polling.
 */
public class OperationProgress {

    // Single volatile reference to immutable state ensures atomic reads
    private volatile ProgressState state = ProgressState.empty();

    public void reset() {
        state = ProgressState.empty();
    }

    public void start(String operation, int totalSteps) {
        state = state.start(operation, totalSteps);
    }

    public void update(int step, String message) {
        state = state.update(step, message);
    }

    public void finish(boolean success, String message) {
        state = state.finish(success, message);
    }

    public Map<String, Object> toMap() {
        return state.toMap();
    }

    // Accessors for individual fields (read from immutable state)
    public String getOperation() {
        return state.operation;
    }

    public boolean isComplete() {
        return state.complete;
    }

    public boolean isError() {
        return state.error;
    }

    public int getProgress() {
        return state.progress;
    }

    public String getStatus() {
        return state.status;
    }

    /**
     * Immutable state class to prevent torn reads.
     * All state is captured in a single immutable instance for atomic reads.
     */
    public static final class ProgressState {
        final String operation;      // "rolling-update", "snapshot", "compact", null
        final String status;         // Current status message
        final int progress;          // 0-100
        final int currentStep;       // Current step number
        final int totalSteps;        // Total steps
        final long startTime;
        final boolean complete;
        final boolean error;
        final String errorMessage;

        ProgressState(String operation, String status, int progress, int currentStep,
                      int totalSteps, long startTime, boolean complete, boolean error,
                      String errorMessage) {
            this.operation = operation;
            this.status = status;
            this.progress = progress;
            this.currentStep = currentStep;
            this.totalSteps = totalSteps;
            this.startTime = startTime;
            this.complete = complete;
            this.error = error;
            this.errorMessage = errorMessage;
        }

        static ProgressState empty() {
            return new ProgressState(null, null, 0, 0, 0, 0, false, false, null);
        }

        ProgressState start(String op, int steps) {
            return new ProgressState(op, "Starting...", 0, 0, steps, System.currentTimeMillis(), false, false, null);
        }

        ProgressState update(int step, String message) {
            int newProgress = totalSteps > 0 ? (step * 100) / totalSteps : 0;
            return new ProgressState(operation, message, newProgress, step, totalSteps, startTime, false, false, null);
        }

        ProgressState finish(boolean success, String message) {
            return new ProgressState(
                operation, message, success ? 100 : progress, currentStep, totalSteps,
                startTime, true, !success, success ? null : message
            );
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("operation", operation);
            map.put("status", status);
            map.put("progress", progress);
            map.put("currentStep", currentStep);
            map.put("totalSteps", totalSteps);
            map.put("complete", complete);
            map.put("error", error);
            map.put("errorMessage", errorMessage);
            map.put("elapsedMs", startTime > 0 ? System.currentTimeMillis() - startTime : 0);
            return map;
        }
    }
}
