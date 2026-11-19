package com.smarttrading.trading

import com.smarttrading.domain.AccountType

enum class IntegrationStatus { DISCONNECTED, CONNECTED, BLOCKED, UNSUPPORTED }

interface PocketOptionAdapter {
    suspend fun connect(): IntegrationStatus
    suspend fun disconnect()
    fun status(): IntegrationStatus
    fun accountType(): AccountType?
    suspend fun placeTrade(pair: String, direction: String, stake: Double, expirySeconds: Int): Result<String>
}

class BlockedPocketOptionAdapter : PocketOptionAdapter {
    override suspend fun connect() = IntegrationStatus.BLOCKED
    override suspend fun disconnect() = Unit
    override fun status() = IntegrationStatus.BLOCKED
    override fun accountType() = null
    override suspend fun placeTrade(pair: String, direction: String, stake: Double, expirySeconds: Int) =
        Result.failure<String>(UnsupportedOperationException("Pocket Option does not provide a confirmed public execution API"))
}
