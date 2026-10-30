package com.finalysis.ai;

/**
 * The Self-hosted server's running time and estimate (docs/plans/phase-9.md §4.2), computed in Java; the client shows
 * these strings as they are. Never an instance id, an address or a region.
 *
 * @param state          STOPPED, STARTING, RUNNING or STOPPING; null when it can't be read ({@code reason} says why)
 * @param minutesRunning whole minutes since the instance's last start (EC2's LaunchTime); null unless starting or
 *                       running
 * @param hourlyRate     the instance type's on-demand rate in dollars per hour, as configured; null without one
 * @param estimatedCost  minutes × rate / 60 in dollars, half-even to the cent; null without minutes or a rate
 * @param line           "running 42 min · ~$0.70" while it runs; null otherwise
 * @param reason         why the state can't be read (no aws profile, no region, no server, an AWS error); else null
 */
public record SelfHostUsage(String state, Long minutesRunning, String hourlyRate, String estimatedCost, String line,
                            String reason) {

    public static SelfHostUsage unknown(String reason) {
        return new SelfHostUsage(null, null, null, null, null, reason);
    }
}
