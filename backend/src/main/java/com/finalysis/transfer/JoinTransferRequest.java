package com.finalysis.transfer;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/transfers/join} and {@code .../join/dismiss}: a possible match, named by its
 * two one-sided links (docs/plans/phase-3c.md §4.5).
 *
 * @param outLinkId the UNMATCHED link with only an out side
 * @param inLinkId  the UNMATCHED link with only an in side
 */
public record JoinTransferRequest(@NotNull Long outLinkId, @NotNull Long inLinkId) {
}
