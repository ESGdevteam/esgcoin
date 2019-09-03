package brs.feesuggestions

import brs.Block
import brs.BlockchainProcessor
import brs.BlockchainProcessor.Event
import brs.Constants
import brs.DependencyProvider
import brs.db.store.BlockchainStore

import java.util.Arrays

import brs.Constants.FEE_QUANT
import kotlin.math.ceil

class FeeSuggestionCalculator(private val dp: DependencyProvider) { // TODO interface
    // index 0 = oldest, length-1 = newest
    private val latestBlocks = arrayOfNulls<Block>(Constants.FEE_SUGGESTION_MAX_HISTORY_LENGTH)
    private var feeSuggestion = FeeSuggestion(FEE_QUANT, FEE_QUANT, FEE_QUANT)

    init {
        dp.blockchainProcessor.addListener({ this.newBlockApplied(it) }, Event.AFTER_BLOCK_APPLY)
    }

    fun giveFeeSuggestion(): FeeSuggestion {
        if (latestBlocksIsEmpty()) {
            fillInitialHistory()
            recalculateSuggestion()
        }

        return feeSuggestion
    }

    private fun newBlockApplied(block: Block) {
        if (latestBlocksIsEmpty()) {
            fillInitialHistory()
        }

        pushNewBlock(block)
        recalculateSuggestion()
    }

    private fun fillInitialHistory() {
        dp.blockchainStore.getLatestBlocks(latestBlocks.size).forEach { this.pushNewBlock(it) }
    }

    private fun latestBlocksIsEmpty(): Boolean {
        for (latestBlock in latestBlocks) {
            if (latestBlock == null) return true
        }
        return false
    }

    private fun pushNewBlock(block: Block?) {
        if (block == null) return
        // Skip index 0 as we want to remove this one
        if (latestBlocks.size - 1 >= 0) System.arraycopy(latestBlocks, 1, latestBlocks, 0, latestBlocks.size - 1)
        latestBlocks[latestBlocks.size - 1] = block
    }

    private fun recalculateSuggestion() {
        try {
            val transactionSizes = latestBlocks
                    .filterNotNull()
                    .map { it.transactions.size }
            val lowestAmountTransactionsNearHistory = transactionSizes.min() ?: 1
            val averageAmountTransactionsNearHistory = ceil(transactionSizes.average().let { return@let if (it.isNaN()) 1.0 else it }).toLong()
            val highestAmountTransactionsNearHistory = transactionSizes.max() ?: 1

            val cheapFee = (1 + lowestAmountTransactionsNearHistory) * FEE_QUANT
            val standardFee = (1 + averageAmountTransactionsNearHistory) * FEE_QUANT
            val priorityFee = (1 + highestAmountTransactionsNearHistory) * FEE_QUANT

            feeSuggestion = FeeSuggestion(cheapFee, standardFee, priorityFee)
        } catch (ignored: NullPointerException) { // Can happen if there are less than latestBlocks.length blocks in the blockchain
        }

    }
}
