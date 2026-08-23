package com.esmile.axis.digest.store;

/**
 * Lifecycle of a single daily digest execution.
 *
 * <ul>
 *   <li>{@link #PENDING} — row inserted; fetch + classify + write in flight.</li>
 *   <li>{@link #COMPLETED} — markdown written; {@code articleCount} recorded.</li>
 *   <li>{@link #FAILED} — unrecoverable error during fetch or write.</li>
 * </ul>
 */
public enum DigestExecutionStatus {
    PENDING,
    COMPLETED,
    FAILED
}