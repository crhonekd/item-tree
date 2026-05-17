/**
 * Scheduled delta refresh and full reload (design §7). Contains:
 * <ul>
 *   <li>{@link com.myxcomp.ice.xtree.refresh.RefreshOrchestrator} — body of delta + full reload.</li>
 *   <li>{@link com.myxcomp.ice.xtree.refresh.RefreshScheduler} — {@code @Scheduled} cron wrappers.</li>
 *   <li>{@link com.myxcomp.ice.xtree.refresh.RefreshActuatorEndpoint} — manual trigger via Actuator.</li>
 *   <li>{@link com.myxcomp.ice.xtree.refresh.DeltaReconciler} — per-row diff → {@code apply*}.</li>
 *   <li>{@link com.myxcomp.ice.xtree.refresh.SnapshotDiff} — full-reload drift summary.</li>
 *   <li>Plain holder types ({@code DeltaCounters}, {@code DriftCounters}, {@code RefreshResult}).</li>
 * </ul>
 */
package com.myxcomp.ice.xtree.refresh;
