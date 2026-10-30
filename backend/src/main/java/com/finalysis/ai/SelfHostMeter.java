package com.finalysis.ai;

/**
 * The Self-hosted server's running time and its cost estimate (docs/plans/phase-9.md §4.2), for chat's cost lines.
 * Implemented in {@code ai.selfhost}, the only package that talks to EC2; features depend on this interface only.
 */
public interface SelfHostMeter {

    /** The server as it stands now. Never throws: what can't be read is a {@code reason}. */
    SelfHostUsage usage();
}
