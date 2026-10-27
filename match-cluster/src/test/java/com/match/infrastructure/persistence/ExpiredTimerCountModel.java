// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * RC2 S6 9.2 (FIX-6): the timer bookkeeping of ONE Aeron consensus module, mirrored from Aeron 1.53:
 * <ul>
 *   <li>{@code ConsensusModuleAgent.onScheduleTimer} (:1448-1458): while the expired count of an id is above
 *       zero, a schedule decrements it and is swallowed; otherwise the timer service schedules it;</li>
 *   <li>{@code ConsensusModuleAgent.onReplayTimerEvent} (:1538-1544): a TimerEvent consumed from the log
 *       cancels the pending timer of that id, or, when none is pending, increments the expired count;</li>
 *   <li>{@code WheelTimerService.scheduleTimerForCorrelationId} (:95-102): scheduling a pending id replaces it.</li>
 * </ul>
 * The count map is never pruned and not snapshotted. Only a leader's module polls its timers and appends the
 * TimerEvents it fires; it never feeds its own count.
 */
final class ExpiredTimerCountModel {

    private final Map<Long, Long> pendingDeadlineById = new HashMap<>();
    private final Map<Long, Integer> expiredCountById = new HashMap<>();
    int swallowed;

    void onScheduleTimer(final long correlationId, final long deadline) {
        final int count = expiredCountById.getOrDefault(correlationId, 0);
        if (count == 0) {
            pendingDeadlineById.put(correlationId, deadline); // replace a pending id
        } else {
            expiredCountById.put(correlationId, count - 1);
            swallowed++;
        }
    }

    /** A follower's module consuming a TimerEvent from the log. */
    void onReplayTimerEvent(final long correlationId) {
        if (pendingDeadlineById.remove(correlationId) == null) {
            expiredCountById.merge(correlationId, 1, Integer::sum);
        }
    }

    /** A leader's module: fire every timer due at {@code now}, in deadline order; returns the fired ids. */
    List<Long> poll(final long now) {
        final TreeMap<Long, List<Long>> due = new TreeMap<>();
        for (Map.Entry<Long, Long> e : pendingDeadlineById.entrySet()) {
            if (e.getValue() <= now) {
                due.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
            }
        }
        final List<Long> fired = new ArrayList<>();
        for (List<Long> ids : due.values()) {
            for (long id : ids) {
                pendingDeadlineById.remove(id);
                fired.add(id);
            }
        }
        return fired;
    }

    boolean isPending(final long correlationId) {
        return pendingDeadlineById.containsKey(correlationId);
    }

    int pendingCount() {
        return pendingDeadlineById.size();
    }

    int expiredCount(final long correlationId) {
        return expiredCountById.getOrDefault(correlationId, 0);
    }
}
