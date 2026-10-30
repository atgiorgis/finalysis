/**
 * The Self-hosted server's controls (docs/plans/phase-9.md §4.2, §5.4, §5.5): EC2 Start, Stop and status for the one
 * instance tagged {@code finalysis:role=selfhost}, its running time and cost estimate, and Settings' Test button. The
 * only package that names AWS SDK EC2 classes ({@code PackageDependencyTest}); nothing outside it depends on it, and
 * chat reads the cost through {@link com.finalysis.ai.SelfHostMeter}.
 *
 * <p>The app holds the server's Tailscale name, its region and the tag; never a key, an instance id or an address
 * beyond what each request finds. Creating and destroying the server are {@code scripts/selfhost/}'s, run by the owner.
 */
package com.finalysis.ai.selfhost;
