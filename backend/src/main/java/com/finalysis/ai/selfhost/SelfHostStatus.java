package com.finalysis.ai.selfhost;

/**
 * The Self-hosted server for Settings → Corgi → Self-hosted (docs/plans/phase-9.md §5.5): its state, the start's
 * progress, its running time and estimate (computed in Java, shown as they are), and why Start and Stop are disabled
 * when they are. Never an instance id, an address or a region.
 *
 * @param controls       whether Start and Stop can be used: Cloud credentials, a region and exactly one tagged server
 * @param reason         why not, in plain words; null when they can
 * @param state          STOPPED, STARTING, RUNNING or STOPPING; null when the server can't be read
 * @param progress       while STARTING: "instance starting", "waiting for Tailscale" or "loading the model"
 * @param instanceType   the server's instance type, e.g. g6.xlarge
 * @param minutesRunning whole minutes since its last start (EC2's LaunchTime); null while stopped
 * @param hourlyRate     its type's on-demand rate in dollars per hour, as configured; null without one
 * @param estimatedCost  minutes × rate / 60 in dollars, half-even to the cent; null without minutes or a rate
 * @param line           "running 42 min · ~$0.70" while starting or running, "stopping after 42 min · ~$0.70" while
 *                       stopping; null while stopped
 * @param storageNote    the disk's cost while stopped, with its size
 * @param note           something to know that isn't a state: the model didn't load after a start, or Ollama didn't
 *                       answer within ten minutes of it; null otherwise
 */
public record SelfHostStatus(boolean controls, String reason, String state, String progress, String instanceType,
                             Long minutesRunning, String hourlyRate, String estimatedCost, String line,
                             String storageNote, String note) {

    static SelfHostStatus unavailable(String reason) {
        return new SelfHostStatus(false, reason, null, null, null, null, null, null, null, null, null);
    }
}
