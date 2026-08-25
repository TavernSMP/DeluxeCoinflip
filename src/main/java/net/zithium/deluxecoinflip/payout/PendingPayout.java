/*
 * DeluxeCoinflip Plugin
 * Copyright (c) 2021 - 2022 Lewis D (ItsLewizzz). All rights reserved.
 */

package net.zithium.deluxecoinflip.payout;

import java.util.UUID;

/**
 * Money owed to a player that could not be paid at the moment it was earned.
 *
 * @param id        unique id of this payout, used to settle it later
 * @param player    who the money belongs to
 * @param amount    how much is owed
 * @param provider  economy provider identifier the amount belongs to
 * @param createdAt epoch millis, for diagnostics only
 */
public record PendingPayout(UUID id, UUID player, double amount, String provider, long createdAt) {
}
