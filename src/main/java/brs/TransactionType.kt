package brs

import brs.Attachment.AbstractAttachment
import brs.Attachment.AutomatedTransactionsCreation
import brs.BurstException.NotValidException
import brs.BurstException.ValidationException
import brs.at.AT
import brs.at.AtConstants
import brs.at.AtController
import brs.at.AtException
import brs.fluxcapacitor.FluxValues
import brs.transactionduplicates.TransactionDuplicationKey
import brs.util.Convert
import brs.util.JSON
import brs.util.TextUtils
import com.google.gson.JsonObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.ByteBuffer
import java.util.*

import brs.Constants.FEE_QUANT
import brs.Constants.ONE_BURST

abstract class TransactionType private constructor() {

    abstract val type: Byte

    abstract val subtype: Byte

    abstract val description: String

    val isSigned: Boolean
        get() = true

    @Throws(BurstException.NotValidException::class)
    abstract fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.AbstractAttachment

    @Throws(BurstException.NotValidException::class)
    internal abstract fun parseAttachment(attachmentData: JsonObject): Attachment.AbstractAttachment

    @Throws(BurstException.ValidationException::class)
    internal abstract fun validateAttachment(transaction: Transaction)

    // return false if double spending
    fun applyUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
        val totalAmountNQT = calculateTransactionAmountNQT(transaction)!!
        if (logger.isTraceEnabled) {
            logger.trace("applyUnconfirmed: {} < totalamount: {} = false", senderAccount.unconfirmedBalanceNQT, totalAmountNQT)
        }
        if (senderAccount.unconfirmedBalanceNQT < totalAmountNQT) {
            return false
        }
        dp!!.accountService.addToUnconfirmedBalanceNQT(senderAccount, -totalAmountNQT)
        if (!applyAttachmentUnconfirmed(transaction, senderAccount)) {
            if (logger.isTraceEnabled) {
                logger.trace("!applyAttachmentUnconfirmed({}, {})", transaction, senderAccount.id)
            }
            dp!!.accountService.addToUnconfirmedBalanceNQT(senderAccount, totalAmountNQT)
            return false
        }
        return true
    }

    fun calculateTotalAmountNQT(transaction: Transaction): Long? {
        return Convert.safeAdd(calculateTransactionAmountNQT(transaction)!!, calculateAttachmentTotalAmountNQT(transaction)!!)
    }

    private fun calculateTransactionAmountNQT(transaction: Transaction): Long? {
        var totalAmountNQT = Convert.safeAdd(transaction.amountNQT, transaction.feeNQT)
        if (transaction.referencedTransactionFullHash != null && transaction.timestamp > Constants.REFERENCED_TRANSACTION_FULL_HASH_BLOCK_TIMESTAMP) {
            totalAmountNQT = Convert.safeAdd(totalAmountNQT, Constants.UNCONFIRMED_POOL_DEPOSIT_NQT)
        }
        return totalAmountNQT
    }

    protected open fun calculateAttachmentTotalAmountNQT(transaction: Transaction): Long? {
        return 0L
    }

    internal abstract fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean

    internal fun apply(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
        dp!!.accountService.addToBalanceNQT(senderAccount, -Convert.safeAdd(transaction.amountNQT, transaction.feeNQT))
        if (transaction.referencedTransactionFullHash != null && transaction.timestamp > Constants.REFERENCED_TRANSACTION_FULL_HASH_BLOCK_TIMESTAMP) {
            dp!!.accountService.addToUnconfirmedBalanceNQT(senderAccount, Constants.UNCONFIRMED_POOL_DEPOSIT_NQT)
        }
        if (recipientAccount != null) {
            dp!!.accountService.addToBalanceAndUnconfirmedBalanceNQT(recipientAccount, transaction.amountNQT)
        }
        if (logger.isTraceEnabled) {
            logger.trace("applying transaction - id: {}, type: {}", transaction.id, transaction.type)
        }
        applyAttachment(transaction, senderAccount, recipientAccount)
    }

    internal abstract fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?)

    open fun parseAppendices(builder: Transaction.Builder, attachmentData: JsonObject) {
        builder.message(Appendix.Message.parse(attachmentData))
        builder.encryptedMessage(Appendix.EncryptedMessage.parse(attachmentData))
        builder.publicKeyAnnouncement(Appendix.PublicKeyAnnouncement.parse(attachmentData))
        builder.encryptToSelfMessage(Appendix.EncryptToSelfMessage.parse(attachmentData))
    }

    @Throws(BurstException.ValidationException::class)
    open fun parseAppendices(builder: Transaction.Builder, flags: Int, version: Byte, buffer: ByteBuffer) {
        var position = 1
        if (flags and position != 0) {
            builder.message(Appendix.Message(buffer, version))
        }
        position = position shl 1
        if (flags and position != 0) {
            builder.encryptedMessage(Appendix.EncryptedMessage(buffer, version))
        }
        position = position shl 1
        if (flags and position != 0) {
            builder.publicKeyAnnouncement(Appendix.PublicKeyAnnouncement(buffer, version))
        }
        position = position shl 1
        if (flags and position != 0) {
            builder.encryptToSelfMessage(Appendix.EncryptToSelfMessage(buffer, version))
        }
    }

    fun undoUnconfirmed(transaction: Transaction, senderAccount: Account) {
        undoAttachmentUnconfirmed(transaction, senderAccount)
        dp!!.accountService.addToUnconfirmedBalanceNQT(senderAccount, Convert.safeAdd(transaction.amountNQT, transaction.feeNQT))
        if (transaction.referencedTransactionFullHash != null && transaction.timestamp > Constants.REFERENCED_TRANSACTION_FULL_HASH_BLOCK_TIMESTAMP) {
            dp!!.accountService.addToUnconfirmedBalanceNQT(senderAccount, Constants.UNCONFIRMED_POOL_DEPOSIT_NQT)
        }
    }

    internal abstract fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account)

    open fun getDuplicationKey(transaction: Transaction): TransactionDuplicationKey {
        return TransactionDuplicationKey.IS_NEVER_DUPLICATE
    }

    abstract fun hasRecipient(): Boolean

    override fun toString(): String {
        return "type: $type, subtype: $subtype"
    }

    abstract class Payment private constructor() : TransactionType() {

        override val type: Byte
            get() = TransactionType.TYPE_PAYMENT

        override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
            return true
        }

        override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {}

        override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {}

        companion object {

            val ORDINARY: TransactionType = object : Payment() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_PAYMENT_ORDINARY_PAYMENT

                override val description: String
                    get() = "Ordinary Payment"

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.EmptyAttachment {
                    return Attachment.ORDINARY_PAYMENT
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.EmptyAttachment {
                    return Attachment.ORDINARY_PAYMENT
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    if (transaction.amountNQT <= 0 || transaction.amountNQT >= Constants.MAX_BALANCE_NQT) {
                        throw BurstException.NotValidException("Invalid ordinary payment")
                    }
                }

                override fun hasRecipient(): Boolean {
                    return true
                }

            }

            val MULTI_OUT: TransactionType = object : Payment() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_PAYMENT_ORDINARY_PAYMENT_MULTI_OUT

                override val description: String
                    get() = "Multi-out payment"

                @Throws(BurstException.NotValidException::class)
                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.PaymentMultiOutCreation {
                    return Attachment.PaymentMultiOutCreation(buffer, transactionVersion)
                }

                @Throws(BurstException.NotValidException::class)
                override fun parseAttachment(attachmentData: JsonObject): Attachment.PaymentMultiOutCreation {
                    return Attachment.PaymentMultiOutCreation(attachmentData)
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    if (!dp!!.fluxCapacitor.getValue(FluxValues.PRE_DYMAXION, transaction.height.get())) {
                        throw BurstException.NotCurrentlyValidException("Multi Out Payments are not allowed before the Pre Dymaxion block")
                    }

                    val attachment = transaction.attachment as Attachment.PaymentMultiOutCreation
                    val amountNQT = attachment.amountNQT
                    if (amountNQT <= 0
                            || amountNQT >= Constants.MAX_BALANCE_NQT
                            || amountNQT != transaction.amountNQT
                            || attachment.getRecipients().size < 2) {
                        throw BurstException.NotValidException("Invalid multi out payment")
                    }
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.PaymentMultiOutCreation
                    for (recipient in attachment.getRecipients()) {
                        dp!!.accountService.addToBalanceAndUnconfirmedBalanceNQT(dp!!.accountService.getOrAddAccount(recipient[0]), recipient[1])
                    }
                }

                override fun hasRecipient(): Boolean {
                    return false
                }

                override fun parseAppendices(builder: Transaction.Builder, attachmentData: JsonObject) {
                    // No appendices
                }

                override fun parseAppendices(builder: Transaction.Builder, flags: Int, version: Byte, buffer: ByteBuffer) {
                    // No appendices
                }
            }

            val MULTI_SAME_OUT: TransactionType = object : Payment() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_PAYMENT_ORDINARY_PAYMENT_MULTI_SAME_OUT

                override val description: String
                    get() = "Multi-out Same Payment"

                @Throws(BurstException.NotValidException::class)
                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.PaymentMultiSameOutCreation {
                    return Attachment.PaymentMultiSameOutCreation(buffer, transactionVersion)
                }

                @Throws(BurstException.NotValidException::class)
                override fun parseAttachment(attachmentData: JsonObject): Attachment.PaymentMultiSameOutCreation {
                    return Attachment.PaymentMultiSameOutCreation(attachmentData)
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    if (!dp!!.fluxCapacitor.getValue(FluxValues.PRE_DYMAXION, transaction.height)) {
                        throw BurstException.NotCurrentlyValidException("Multi Same Out Payments are not allowed before the Pre Dymaxion block")
                    }

                    val attachment = transaction.attachment as Attachment.PaymentMultiSameOutCreation
                    if (attachment.getRecipients().size < 2 && transaction.amountNQT % attachment.getRecipients().size == 0L) {
                        throw BurstException.NotValidException("Invalid multi out payment")
                    }
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.PaymentMultiSameOutCreation
                    val amountNQT = Convert.safeDivide(transaction.amountNQT, attachment.getRecipients().size.toLong())
                    attachment.getRecipients().forEach { a -> dp!!.accountService.addToBalanceAndUnconfirmedBalanceNQT(dp!!.accountService.getOrAddAccount(a), amountNQT) }
                }

                override fun hasRecipient(): Boolean {
                    return false
                }

                override fun parseAppendices(builder: Transaction.Builder, attachmentData: JsonObject) {
                    // No appendices
                }

                override fun parseAppendices(builder: Transaction.Builder, flags: Int, version: Byte, buffer: ByteBuffer) {
                    // No appendices
                }
            }
        }

    }

    abstract class Messaging private constructor() : TransactionType() {

        override val type: Byte
            get() = TransactionType.TYPE_MESSAGING

        override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
            return true
        }

        override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {}

        companion object {

            val ARBITRARY_MESSAGE: TransactionType = object : Messaging() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_MESSAGING_ARBITRARY_MESSAGE

                override val description: String
                    get() = "Arbitrary Message"

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.EmptyAttachment {
                    return Attachment.ARBITRARY_MESSAGE
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.EmptyAttachment {
                    return Attachment.ARBITRARY_MESSAGE
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    // No appendices
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment
                    if (transaction.amountNQT != 0L) {
                        throw BurstException.NotValidException("Invalid arbitrary message: " + JSON.toJsonString(attachment!!.jsonObject))
                    }
                    if (!dp!!.fluxCapacitor.getValue(FluxValues.DIGITAL_GOODS_STORE) && transaction.message == null) {
                        throw BurstException.NotCurrentlyValidException("Missing message appendix not allowed before DGS block")
                    }
                }

                override fun hasRecipient(): Boolean {
                    return true
                }

                @Throws(BurstException.ValidationException::class)
                override fun parseAppendices(builder: Transaction.Builder, flags: Int, version: Byte, buffer: ByteBuffer) {
                    var position = 1
                    if (flags and position != 0 || version.toInt() == 0) {
                        builder.message(Appendix.Message(buffer, version))
                    }
                    position = position shl 1
                    if (flags and position != 0) {
                        builder.encryptedMessage(Appendix.EncryptedMessage(buffer, version))
                    }
                    position = position shl 1
                    if (flags and position != 0) {
                        builder.publicKeyAnnouncement(Appendix.PublicKeyAnnouncement(buffer, version))
                    }
                    position = position shl 1
                    if (flags and position != 0) {
                        builder.encryptToSelfMessage(Appendix.EncryptToSelfMessage(buffer, version))
                    }
                }

            }

            val ALIAS_ASSIGNMENT: TransactionType = object : Messaging() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_MESSAGING_ALIAS_ASSIGNMENT

                override val description: String
                    get() = "Alias Assignment"

                @Throws(BurstException.NotValidException::class)
                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.MessagingAliasAssignment {
                    return Attachment.MessagingAliasAssignment(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.MessagingAliasAssignment {
                    return Attachment.MessagingAliasAssignment(attachmentData)
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.MessagingAliasAssignment
                    dp!!.aliasService.addOrUpdateAlias(transaction, attachment)
                }

                override fun getDuplicationKey(transaction: Transaction): TransactionDuplicationKey {
                    val attachment = transaction.attachment as Attachment.MessagingAliasAssignment
                    return TransactionDuplicationKey(Messaging.ALIAS_ASSIGNMENT, attachment.aliasName.toLowerCase(Locale.ENGLISH))
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.MessagingAliasAssignment
                    if (attachment.aliasName.isEmpty()
                            || Convert.toBytes(attachment.aliasName).size > Constants.MAX_ALIAS_LENGTH
                            || attachment.aliasURI.length > Constants.MAX_ALIAS_URI_LENGTH) {
                        throw BurstException.NotValidException("Invalid alias assignment: " + JSON.toJsonString(attachment.jsonObject))
                    }
                    if (!TextUtils.isInAlphabet(attachment.aliasName)) {
                        throw BurstException.NotValidException("Invalid alias name: " + attachment.aliasName)
                    }
                    val alias = dp!!.aliasService.getAlias(attachment.aliasName)
                    if (alias != null && alias.accountId != transaction.senderId) {
                        throw BurstException.NotCurrentlyValidException("Alias already owned by another account: " + attachment.aliasName)
                    }
                }

                override fun hasRecipient(): Boolean {
                    return false
                }

            }

            val ALIAS_SELL: TransactionType = object : Messaging() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_MESSAGING_ALIAS_SELL

                override val description: String
                    get() = "Alias Sell"

                @Throws(BurstException.NotValidException::class)
                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.MessagingAliasSell {
                    return Attachment.MessagingAliasSell(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.MessagingAliasSell {
                    return Attachment.MessagingAliasSell(attachmentData)
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.MessagingAliasSell
                    dp!!.aliasService.sellAlias(transaction, attachment)
                }

                override fun getDuplicationKey(transaction: Transaction): TransactionDuplicationKey {
                    val attachment = transaction.attachment as Attachment.MessagingAliasSell
                    // not a bug, uniqueness is based on Messaging.ALIAS_ASSIGNMENT
                    return TransactionDuplicationKey(Messaging.ALIAS_ASSIGNMENT, attachment.aliasName.toLowerCase(Locale.ENGLISH))
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    if (!dp!!.fluxCapacitor.getValue(FluxValues.DIGITAL_GOODS_STORE, dp!!.blockchain.lastBlock.height)) {
                        throw BurstException.NotYetEnabledException("Alias transfer not yet enabled at height " + dp!!.blockchain.lastBlock.height)
                    }
                    if (transaction.amountNQT != 0L) {
                        throw BurstException.NotValidException("Invalid sell alias transaction: " + JSON.toJsonString(transaction.jsonObject))
                    }
                    val attachment = transaction.attachment as Attachment.MessagingAliasSell
                    val aliasName = attachment.aliasName
                    if (aliasName == null || aliasName.isEmpty()) {
                        throw BurstException.NotValidException("Missing alias name")
                    }
                    val priceNQT = attachment.priceNQT
                    if (priceNQT < 0 || priceNQT > Constants.MAX_BALANCE_NQT) {
                        throw BurstException.NotValidException("Invalid alias sell price: $priceNQT")
                    }
                    if (priceNQT == 0L) {
                        if (Genesis.CREATOR_ID == transaction.recipientId) {
                            throw BurstException.NotValidException("Transferring aliases to Genesis account not allowed")
                        } else if (transaction.recipientId == 0L) {
                            throw BurstException.NotValidException("Missing alias transfer recipient")
                        }
                    }
                    val alias = dp!!.aliasService.getAlias(aliasName)
                    if (alias == null) {
                        throw BurstException.NotCurrentlyValidException("Alias hasn't been registered yet: $aliasName")
                    } else if (alias.accountId != transaction.senderId) {
                        throw BurstException.NotCurrentlyValidException("Alias doesn't belong to sender: $aliasName")
                    }
                }

                override fun hasRecipient(): Boolean {
                    return true
                }

            }

            val ALIAS_BUY: TransactionType = object : Messaging() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_MESSAGING_ALIAS_BUY

                override val description: String
                    get() = "Alias Buy"

                @Throws(BurstException.NotValidException::class)
                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.MessagingAliasBuy {
                    return Attachment.MessagingAliasBuy(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.MessagingAliasBuy {
                    return Attachment.MessagingAliasBuy(attachmentData)
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.MessagingAliasBuy
                    val aliasName = attachment.aliasName
                    dp!!.aliasService.changeOwner(transaction.senderId, aliasName, transaction.blockTimestamp.get())
                }

                override fun getDuplicationKey(transaction: Transaction): TransactionDuplicationKey {
                    val attachment = transaction.attachment as Attachment.MessagingAliasBuy
                    // not a bug, uniqueness is based on Messaging.ALIAS_ASSIGNMENT
                    return TransactionDuplicationKey(Messaging.ALIAS_ASSIGNMENT, attachment.aliasName.toLowerCase(Locale.ENGLISH))
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    if (!dp!!.fluxCapacitor.getValue(FluxValues.DIGITAL_GOODS_STORE, dp!!.blockchain.lastBlock.height)) {
                        throw BurstException.NotYetEnabledException("Alias transfer not yet enabled at height " + dp!!.blockchain.lastBlock.height)
                    }
                    val attachment = transaction.attachment as Attachment.MessagingAliasBuy
                    val aliasName = attachment.aliasName
                    val alias = dp!!.aliasService.getAlias(aliasName)
                    if (alias == null) {
                        throw BurstException.NotCurrentlyValidException("Alias hasn't been registered yet: $aliasName")
                    } else if (alias.accountId != transaction.recipientId) {
                        throw BurstException.NotCurrentlyValidException("Alias is owned by account other than recipient: " + Convert.toUnsignedLong(alias.accountId))
                    }
                    val offer = dp!!.aliasService.getOffer(alias)
                            ?: throw BurstException.NotCurrentlyValidException("Alias is not for sale: $aliasName")
                    if (transaction.amountNQT < offer.priceNQT) {
                        val msg = ("Price is too low for: " + aliasName + " ("
                                + transaction.amountNQT + " < " + offer.priceNQT + ")")
                        throw BurstException.NotCurrentlyValidException(msg)
                    }
                    if (offer.buyerId != 0L && offer.buyerId != transaction.senderId) {
                        throw BurstException.NotCurrentlyValidException("Wrong buyer for " + aliasName + ": "
                                + Convert.toUnsignedLong(transaction.senderId) + " expected: "
                                + Convert.toUnsignedLong(offer.buyerId))
                    }
                }

                override fun hasRecipient(): Boolean {
                    return true
                }

            }

            val ACCOUNT_INFO: Messaging = object : Messaging() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_MESSAGING_ACCOUNT_INFO

                override val description: String
                    get() = "Account Info"

                @Throws(BurstException.NotValidException::class)
                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.MessagingAccountInfo {
                    return Attachment.MessagingAccountInfo(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.MessagingAccountInfo {
                    return Attachment.MessagingAccountInfo(attachmentData)
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.MessagingAccountInfo
                    if (Convert.toBytes(attachment.name).size > Constants.MAX_ACCOUNT_NAME_LENGTH || Convert.toBytes(attachment.description).size > Constants.MAX_ACCOUNT_DESCRIPTION_LENGTH) {
                        throw BurstException.NotValidException("Invalid account info issuance: " + JSON.toJsonString(attachment.jsonObject))
                    }
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.MessagingAccountInfo
                    dp!!.accountService.setAccountInfo(senderAccount, attachment.name, attachment.description)
                }

                override fun hasRecipient(): Boolean {
                    return false
                }

            }
        }

    }

    abstract class ColoredCoins private constructor() : TransactionType() {

        override val type: Byte
            get() = TransactionType.TYPE_COLORED_COINS

        internal abstract class ColoredCoinsOrderPlacement : ColoredCoins() {

            @Throws(BurstException.ValidationException::class)
            override fun validateAttachment(transaction: Transaction) {
                val attachment = transaction.attachment as Attachment.ColoredCoinsOrderPlacement
                if (attachment.priceNQT <= 0 || attachment.priceNQT > Constants.MAX_BALANCE_NQT
                        || attachment.assetId == 0L) {
                    throw BurstException.NotValidException("Invalid asset order placement: " + JSON.toJsonString(attachment.jsonObject))
                }
                val asset = dp!!.assetExchange.getAsset(attachment.assetId)
                if (attachment.quantityQNT <= 0 || asset != null && attachment.quantityQNT > asset.quantityQNT) {
                    throw BurstException.NotValidException("Invalid asset order placement asset or quantity: " + JSON.toJsonString(attachment.jsonObject))
                }
                if (asset == null) {
                    throw BurstException.NotCurrentlyValidException("Asset " + Convert.toUnsignedLong(attachment.assetId) +
                            " does not exist yet")
                }
            }

            override fun hasRecipient(): Boolean {
                return false
            }

        }

        internal abstract class ColoredCoinsOrderCancellation : ColoredCoins() {

            override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
                return true
            }

            override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {}

            override fun hasRecipient(): Boolean {
                return false
            }

        }

        companion object {

            val ASSET_ISSUANCE: TransactionType = object : ColoredCoins() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_COLORED_COINS_ASSET_ISSUANCE

                override val description: String
                    get() = "Asset Issuance"

                public override fun getBaselineFee(height: Int): Fee {
                    return BASELINE_ASSET_ISSUANCE_FEE
                }

                @Throws(BurstException.NotValidException::class)
                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.ColoredCoinsAssetIssuance {
                    return Attachment.ColoredCoinsAssetIssuance(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.ColoredCoinsAssetIssuance {
                    return Attachment.ColoredCoinsAssetIssuance(attachmentData)
                }

                override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
                    return true
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.ColoredCoinsAssetIssuance
                    val assetId = transaction.id
                    dp!!.assetExchange.addAsset(transaction, attachment)
                    dp!!.accountService.addToAssetAndUnconfirmedAssetBalanceQNT(senderAccount, assetId, attachment.quantityQNT)
                }

                override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {
                    // Nothing to undo
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.ColoredCoinsAssetIssuance
                    if (attachment.name!!.length < Constants.MIN_ASSET_NAME_LENGTH
                            || attachment.name.length > Constants.MAX_ASSET_NAME_LENGTH
                            || attachment.description.length > Constants.MAX_ASSET_DESCRIPTION_LENGTH
                            || attachment.decimals < 0 || attachment.decimals > 8
                            || attachment.quantityQNT <= 0
                            || attachment.quantityQNT > Constants.MAX_ASSET_QUANTITY_QNT) {
                        throw BurstException.NotValidException("Invalid asset issuance: " + JSON.toJsonString(attachment.jsonObject))
                    }
                    if (!TextUtils.isInAlphabet(attachment.name)) {
                        throw BurstException.NotValidException("Invalid asset name: " + attachment.name)
                    }
                }

                override fun hasRecipient(): Boolean {
                    return false
                }

            }

            val ASSET_TRANSFER: TransactionType = object : ColoredCoins() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_COLORED_COINS_ASSET_TRANSFER

                override val description: String
                    get() = "Asset Transfer"

                @Throws(BurstException.NotValidException::class)
                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.ColoredCoinsAssetTransfer {
                    return Attachment.ColoredCoinsAssetTransfer(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.ColoredCoinsAssetTransfer {
                    return Attachment.ColoredCoinsAssetTransfer(attachmentData)
                }

                override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
                    logger.trace("TransactionType ASSET_TRANSFER")
                    val attachment = transaction.attachment as Attachment.ColoredCoinsAssetTransfer
                    val unconfirmedAssetBalance = dp!!.accountService.getUnconfirmedAssetBalanceQNT(senderAccount, attachment.assetId)
                    if (unconfirmedAssetBalance >= 0 && unconfirmedAssetBalance >= attachment.quantityQNT) {
                        dp!!.accountService.addToUnconfirmedAssetBalanceQNT(senderAccount, attachment.assetId, -attachment.quantityQNT)
                        return true
                    }
                    return false
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.ColoredCoinsAssetTransfer
                    dp!!.accountService.addToAssetBalanceQNT(senderAccount, attachment.assetId, -attachment.quantityQNT)
                    dp!!.accountService.addToAssetAndUnconfirmedAssetBalanceQNT(recipientAccount, attachment.assetId, attachment.quantityQNT)
                    dp!!.assetExchange.addAssetTransfer(transaction, attachment)
                }

                override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {
                    val attachment = transaction.attachment as Attachment.ColoredCoinsAssetTransfer
                    dp!!.accountService.addToUnconfirmedAssetBalanceQNT(senderAccount, attachment.assetId, attachment.quantityQNT)
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.ColoredCoinsAssetTransfer
                    if (transaction.amountNQT != 0L
                            || attachment.comment != null && attachment.comment.length > Constants.MAX_ASSET_TRANSFER_COMMENT_LENGTH
                            || attachment.assetId == 0L) {
                        throw BurstException.NotValidException("Invalid asset transfer amount or comment: " + JSON.toJsonString(attachment.jsonObject))
                    }
                    if (transaction.version > 0 && attachment.comment != null) {
                        throw BurstException.NotValidException("Asset transfer comments no longer allowed, use message " + "or encrypted message appendix instead")
                    }
                    val asset = dp!!.assetExchange.getAsset(attachment.assetId)
                    if (attachment.quantityQNT <= 0 || asset != null && attachment.quantityQNT > asset.quantityQNT) {
                        throw BurstException.NotValidException("Invalid asset transfer asset or quantity: " + JSON.toJsonString(attachment.jsonObject))
                    }
                    if (asset == null) {
                        throw BurstException.NotCurrentlyValidException("Asset " + Convert.toUnsignedLong(attachment.assetId) +
                                " does not exist yet")
                    }
                }

                override fun hasRecipient(): Boolean {
                    return true
                }

            }

            val ASK_ORDER_PLACEMENT: TransactionType = object : ColoredCoinsOrderPlacement() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_COLORED_COINS_ASK_ORDER_PLACEMENT

                override val description: String
                    get() = "Ask Order Placement"

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.ColoredCoinsAskOrderPlacement {
                    return Attachment.ColoredCoinsAskOrderPlacement(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.ColoredCoinsAskOrderPlacement {
                    return Attachment.ColoredCoinsAskOrderPlacement(attachmentData)
                }

                override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
                    logger.trace("TransactionType ASK_ORDER_PLACEMENT")
                    val attachment = transaction.attachment as Attachment.ColoredCoinsAskOrderPlacement
                    val unconfirmedAssetBalance = dp!!.accountService.getUnconfirmedAssetBalanceQNT(senderAccount, attachment.assetId)
                    if (unconfirmedAssetBalance >= 0 && unconfirmedAssetBalance >= attachment.quantityQNT) {
                        dp!!.accountService.addToUnconfirmedAssetBalanceQNT(senderAccount, attachment.assetId, -attachment.quantityQNT)
                        return true
                    }
                    return false
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.ColoredCoinsAskOrderPlacement
                    if (dp!!.assetExchange.getAsset(attachment.assetId) != null) {
                        dp!!.assetExchange.addAskOrder(transaction, attachment)
                    }
                }

                override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {
                    val attachment = transaction.attachment as Attachment.ColoredCoinsAskOrderPlacement
                    dp!!.accountService.addToUnconfirmedAssetBalanceQNT(senderAccount, attachment.assetId, attachment.quantityQNT)
                }

            }

            val BID_ORDER_PLACEMENT: TransactionType = object : ColoredCoinsOrderPlacement() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_COLORED_COINS_BID_ORDER_PLACEMENT

                override val description: String
                    get() = "Bid Order Placement"

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.ColoredCoinsBidOrderPlacement {
                    return Attachment.ColoredCoinsBidOrderPlacement(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.ColoredCoinsBidOrderPlacement {
                    return Attachment.ColoredCoinsBidOrderPlacement(attachmentData)
                }

                override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
                    logger.trace("TransactionType BID_ORDER_PLACEMENT")
                    val totalAmountNQT = calculateAttachmentTotalAmountNQT(transaction)
                    if (senderAccount.unconfirmedBalanceNQT >= totalAmountNQT) {
                        dp!!.accountService.addToUnconfirmedBalanceNQT(senderAccount, -totalAmountNQT)
                        return true
                    }
                    return false
                }

                public override fun calculateAttachmentTotalAmountNQT(transaction: Transaction): Long {
                    val attachment = transaction.attachment as Attachment.ColoredCoinsBidOrderPlacement
                    return Convert.safeMultiply(attachment.quantityQNT, attachment.priceNQT)
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.ColoredCoinsBidOrderPlacement
                    if (dp!!.assetExchange.getAsset(attachment.assetId) != null) {
                        dp!!.assetExchange.addBidOrder(transaction, attachment)
                    }
                }

                override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {
                    val totalAmountNQT = calculateAttachmentTotalAmountNQT(transaction)
                    dp!!.accountService.addToUnconfirmedBalanceNQT(senderAccount, totalAmountNQT)
                }

            }

            val ASK_ORDER_CANCELLATION: TransactionType = object : ColoredCoinsOrderCancellation() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_COLORED_COINS_ASK_ORDER_CANCELLATION

                override val description: String
                    get() = "Ask Order Cancellation"

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.ColoredCoinsAskOrderCancellation {
                    return Attachment.ColoredCoinsAskOrderCancellation(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.ColoredCoinsAskOrderCancellation {
                    return Attachment.ColoredCoinsAskOrderCancellation(attachmentData)
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.ColoredCoinsAskOrderCancellation
                    val order = dp!!.assetExchange.getAskOrder(attachment.orderId)
                    dp!!.assetExchange.removeAskOrder(attachment.orderId)
                    if (order != null) {
                        dp!!.accountService.addToUnconfirmedAssetBalanceQNT(senderAccount, order.assetId, order.quantityQNT)
                    }
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.ColoredCoinsAskOrderCancellation
                    val ask = dp!!.assetExchange.getAskOrder(attachment.orderId)
                            ?: throw BurstException.NotCurrentlyValidException("Invalid ask order: " + Convert.toUnsignedLong(attachment.orderId))
                    if (ask.accountId != transaction.senderId) {
                        throw BurstException.NotValidException("Order " + Convert.toUnsignedLong(attachment.orderId) + " was created by account "
                                + Convert.toUnsignedLong(ask.accountId))
                    }
                }

            }

            val BID_ORDER_CANCELLATION: TransactionType = object : ColoredCoinsOrderCancellation() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_COLORED_COINS_BID_ORDER_CANCELLATION

                override val description: String
                    get() = "Bid Order Cancellation"

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.ColoredCoinsBidOrderCancellation {
                    return Attachment.ColoredCoinsBidOrderCancellation(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.ColoredCoinsBidOrderCancellation {
                    return Attachment.ColoredCoinsBidOrderCancellation(attachmentData)
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.ColoredCoinsBidOrderCancellation
                    val order = dp!!.assetExchange.getBidOrder(attachment.orderId)
                    dp!!.assetExchange.removeBidOrder(attachment.orderId)
                    if (order != null) {
                        dp!!.accountService.addToUnconfirmedBalanceNQT(senderAccount, Convert.safeMultiply(order.quantityQNT, order.priceNQT))
                    }
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.ColoredCoinsBidOrderCancellation
                    val bid = dp!!.assetExchange.getBidOrder(attachment.orderId)
                            ?: throw BurstException.NotCurrentlyValidException("Invalid bid order: " + Convert.toUnsignedLong(attachment.orderId))
                    if (bid.accountId != transaction.senderId) {
                        throw BurstException.NotValidException("Order " + Convert.toUnsignedLong(attachment.orderId) + " was created by account "
                                + Convert.toUnsignedLong(bid.accountId))
                    }
                }

            }
        }
    }

    abstract class DigitalGoods private constructor() : TransactionType() {

        override val type: Byte
            get() = TransactionType.TYPE_DIGITAL_GOODS

        override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
            return true
        }

        override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {}

        @Throws(BurstException.ValidationException::class)
        override fun validateAttachment(transaction: Transaction) {
            if (!dp!!.fluxCapacitor.getValue(FluxValues.DIGITAL_GOODS_STORE, dp!!.blockchain.lastBlock.height)) {
                throw BurstException.NotYetEnabledException("Digital goods listing not yet enabled at height " + dp!!.blockchain.lastBlock.height)
            }
            if (transaction.amountNQT != 0L) {
                throw BurstException.NotValidException("Invalid digital goods transaction")
            }
            doValidateAttachment(transaction)
        }

        @Throws(BurstException.ValidationException::class)
        internal abstract fun doValidateAttachment(transaction: Transaction)

        companion object {


            val LISTING: TransactionType = object : DigitalGoods() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_DIGITAL_GOODS_LISTING

                override val description: String
                    get() = "Listing"

                @Throws(BurstException.NotValidException::class)
                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.DigitalGoodsListing {
                    return Attachment.DigitalGoodsListing(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.DigitalGoodsListing {
                    return Attachment.DigitalGoodsListing(attachmentData)
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsListing
                    dp!!.digitalGoodsStoreService.listGoods(transaction, attachment)
                }

                @Throws(BurstException.ValidationException::class)
                override fun doValidateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsListing
                    if (attachment.name!!.isEmpty()
                            || attachment.name.length > Constants.MAX_DGS_LISTING_NAME_LENGTH
                            || attachment.description!!.length > Constants.MAX_DGS_LISTING_DESCRIPTION_LENGTH
                            || attachment.tags!!.length > Constants.MAX_DGS_LISTING_TAGS_LENGTH
                            || attachment.quantity < 0 || attachment.quantity > Constants.MAX_DGS_LISTING_QUANTITY
                            || attachment.priceNQT <= 0 || attachment.priceNQT > Constants.MAX_BALANCE_NQT) {
                        throw BurstException.NotValidException("Invalid digital goods listing: " + JSON.toJsonString(attachment.jsonObject))
                    }
                }

                override fun hasRecipient(): Boolean {
                    return false
                }

            }

            val DELISTING: TransactionType = object : DigitalGoods() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_DIGITAL_GOODS_DELISTING

                override val description: String
                    get() = "Delisting"

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.DigitalGoodsDelisting {
                    return Attachment.DigitalGoodsDelisting(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.DigitalGoodsDelisting {
                    return Attachment.DigitalGoodsDelisting(attachmentData)
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsDelisting
                    dp!!.digitalGoodsStoreService.delistGoods(attachment.goodsId)
                }

                @Throws(BurstException.ValidationException::class)
                override fun doValidateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsDelisting
                    val goods = dp!!.digitalGoodsStoreService.getGoods(attachment.goodsId)
                    if (goods != null && transaction.senderId != goods.sellerId) {
                        throw BurstException.NotValidException("Invalid digital goods delisting - seller is different: " + JSON.toJsonString(attachment.jsonObject))
                    }
                    if (goods == null || goods.isDelisted) {
                        throw BurstException.NotCurrentlyValidException("Goods " + Convert.toUnsignedLong(attachment.goodsId) +
                                "not yet listed or already delisted")
                    }
                }

                override fun getDuplicationKey(transaction: Transaction): TransactionDuplicationKey {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsDelisting
                    return TransactionDuplicationKey(DigitalGoods.DELISTING, Convert.toUnsignedLong(attachment.goodsId))
                }

                override fun hasRecipient(): Boolean {
                    return false
                }

            }

            val PRICE_CHANGE: TransactionType = object : DigitalGoods() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_DIGITAL_GOODS_PRICE_CHANGE

                override val description: String
                    get() = "Price Change"

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.DigitalGoodsPriceChange {
                    return Attachment.DigitalGoodsPriceChange(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.DigitalGoodsPriceChange {
                    return Attachment.DigitalGoodsPriceChange(attachmentData)
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsPriceChange
                    dp!!.digitalGoodsStoreService.changePrice(attachment.goodsId, attachment.priceNQT)
                }

                @Throws(BurstException.ValidationException::class)
                override fun doValidateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsPriceChange
                    val goods = dp!!.digitalGoodsStoreService.getGoods(attachment.goodsId)
                    if (attachment.priceNQT <= 0 || attachment.priceNQT > Constants.MAX_BALANCE_NQT
                            || goods != null && transaction.senderId != goods.sellerId) {
                        throw BurstException.NotValidException("Invalid digital goods price change: " + JSON.toJsonString(attachment.jsonObject))
                    }
                    if (goods == null || goods.isDelisted) {
                        throw BurstException.NotCurrentlyValidException("Goods " + Convert.toUnsignedLong(attachment.goodsId) +
                                "not yet listed or already delisted")
                    }
                }

                override fun getDuplicationKey(transaction: Transaction): TransactionDuplicationKey {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsPriceChange
                    // not a bug, uniqueness is based on DigitalGoods.DELISTING
                    return TransactionDuplicationKey(DigitalGoods.DELISTING, Convert.toUnsignedLong(attachment.goodsId))
                }

                override fun hasRecipient(): Boolean {
                    return false
                }

            }

            val QUANTITY_CHANGE: TransactionType = object : DigitalGoods() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_DIGITAL_GOODS_QUANTITY_CHANGE

                override val description: String
                    get() = "Quantity Change"

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.DigitalGoodsQuantityChange {
                    return Attachment.DigitalGoodsQuantityChange(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.DigitalGoodsQuantityChange {
                    return Attachment.DigitalGoodsQuantityChange(attachmentData)
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsQuantityChange
                    dp!!.digitalGoodsStoreService.changeQuantity(attachment.goodsId, attachment.deltaQuantity, false)
                }

                @Throws(BurstException.ValidationException::class)
                override fun doValidateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsQuantityChange
                    val goods = dp!!.digitalGoodsStoreService.getGoods(attachment.goodsId)
                    if (attachment.deltaQuantity < -Constants.MAX_DGS_LISTING_QUANTITY
                            || attachment.deltaQuantity > Constants.MAX_DGS_LISTING_QUANTITY
                            || goods != null && transaction.senderId != goods.sellerId) {
                        throw BurstException.NotValidException("Invalid digital goods quantity change: " + JSON.toJsonString(attachment.jsonObject))
                    }
                    if (goods == null || goods.isDelisted) {
                        throw BurstException.NotCurrentlyValidException("Goods " + Convert.toUnsignedLong(attachment.goodsId) +
                                "not yet listed or already delisted")
                    }
                }

                override fun getDuplicationKey(transaction: Transaction): TransactionDuplicationKey {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsQuantityChange
                    // not a bug, uniqueness is based on DigitalGoods.DELISTING
                    return TransactionDuplicationKey(DigitalGoods.DELISTING, Convert.toUnsignedLong(attachment.goodsId))
                }

                override fun hasRecipient(): Boolean {
                    return false
                }

            }

            val PURCHASE: TransactionType = object : DigitalGoods() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_DIGITAL_GOODS_PURCHASE

                override val description: String
                    get() = "Purchase"

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.DigitalGoodsPurchase {
                    return Attachment.DigitalGoodsPurchase(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.DigitalGoodsPurchase {
                    return Attachment.DigitalGoodsPurchase(attachmentData)
                }

                override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
                    logger.trace("TransactionType PURCHASE")
                    val totalAmountNQT = calculateAttachmentTotalAmountNQT(transaction)
                    if (senderAccount.unconfirmedBalanceNQT >= totalAmountNQT) {
                        dp!!.accountService.addToUnconfirmedBalanceNQT(senderAccount, -totalAmountNQT)
                        return true
                    }
                    return false
                }

                public override fun calculateAttachmentTotalAmountNQT(transaction: Transaction): Long {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsPurchase
                    return Convert.safeMultiply(attachment.quantity.toLong(), attachment.priceNQT)
                }

                override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {
                    dp!!.accountService.addToUnconfirmedBalanceNQT(senderAccount, calculateAttachmentTotalAmountNQT(transaction))
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsPurchase
                    dp!!.digitalGoodsStoreService.purchase(transaction, attachment)
                }

                @Throws(BurstException.ValidationException::class)
                override fun doValidateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsPurchase
                    val goods = dp!!.digitalGoodsStoreService.getGoods(attachment.goodsId)
                    if (attachment.quantity <= 0 || attachment.quantity > Constants.MAX_DGS_LISTING_QUANTITY
                            || attachment.priceNQT <= 0 || attachment.priceNQT > Constants.MAX_BALANCE_NQT
                            || goods != null && goods.sellerId != transaction.recipientId) {
                        throw BurstException.NotValidException("Invalid digital goods purchase: " + JSON.toJsonString(attachment.jsonObject))
                    }
                    if (transaction.encryptedMessage != null && !transaction.encryptedMessage!!.isText) {
                        throw BurstException.NotValidException("Only text encrypted messages allowed")
                    }
                    if (goods == null || goods.isDelisted) {
                        throw BurstException.NotCurrentlyValidException("Goods " + Convert.toUnsignedLong(attachment.goodsId) +
                                "not yet listed or already delisted")
                    }
                    if (attachment.quantity > goods.quantity || attachment.priceNQT != goods.priceNQT) {
                        throw BurstException.NotCurrentlyValidException("Goods price or quantity changed: " + JSON.toJsonString(attachment.jsonObject))
                    }
                    if (attachment.deliveryDeadlineTimestamp <= dp!!.blockchain.lastBlock.timestamp) {
                        throw BurstException.NotCurrentlyValidException("Delivery deadline has already expired: " + attachment.deliveryDeadlineTimestamp)
                    }
                }

                override fun hasRecipient(): Boolean {
                    return true
                }

            }

            val DELIVERY: TransactionType = object : DigitalGoods() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_DIGITAL_GOODS_DELIVERY

                override val description: String
                    get() = "Delivery"

                @Throws(BurstException.NotValidException::class)
                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.DigitalGoodsDelivery {
                    return Attachment.DigitalGoodsDelivery(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.DigitalGoodsDelivery {
                    return Attachment.DigitalGoodsDelivery(attachmentData)
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsDelivery
                    dp!!.digitalGoodsStoreService.deliver(transaction, attachment)
                }

                @Throws(BurstException.ValidationException::class)
                override fun doValidateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsDelivery
                    val purchase = dp!!.digitalGoodsStoreService.getPendingPurchase(attachment.purchaseId)
                    if (attachment.goods.data.size > Constants.MAX_DGS_GOODS_LENGTH
                            || attachment.goods.data.size == 0
                            || attachment.goods.nonce.size != 32
                            || attachment.discountNQT < 0 || attachment.discountNQT > Constants.MAX_BALANCE_NQT
                            || purchase != null && (purchase.buyerId != transaction.recipientId
                                    || transaction.senderId != purchase.sellerId
                                    || attachment.discountNQT > Convert.safeMultiply(purchase.priceNQT, purchase.quantity.toLong()))) {
                        throw BurstException.NotValidException("Invalid digital goods delivery: " + JSON.toJsonString(attachment.jsonObject))
                    }
                    if (purchase == null || purchase.encryptedGoods != null) {
                        throw BurstException.NotCurrentlyValidException("Purchase does not exist yet, or already delivered: " + JSON.toJsonString(attachment.jsonObject))
                    }
                }

                override fun getDuplicationKey(transaction: Transaction): TransactionDuplicationKey {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsDelivery
                    return TransactionDuplicationKey(DigitalGoods.DELIVERY, Convert.toUnsignedLong(attachment.purchaseId))
                }

                override fun hasRecipient(): Boolean {
                    return true
                }

            }

            val FEEDBACK: TransactionType = object : DigitalGoods() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_DIGITAL_GOODS_FEEDBACK

                override val description: String
                    get() = "Feedback"

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.DigitalGoodsFeedback {
                    return Attachment.DigitalGoodsFeedback(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.DigitalGoodsFeedback {
                    return Attachment.DigitalGoodsFeedback(attachmentData)
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsFeedback
                    dp!!.digitalGoodsStoreService.feedback(attachment.purchaseId, transaction.encryptedMessage!!, transaction.message!!)
                }

                @Throws(BurstException.ValidationException::class)
                override fun doValidateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsFeedback
                    val purchase = dp!!.digitalGoodsStoreService.getPurchase(attachment.purchaseId)
                    if (purchase != null && (purchase.sellerId != transaction.recipientId || transaction.senderId != purchase.buyerId)) {
                        throw BurstException.NotValidException("Invalid digital goods feedback: " + JSON.toJsonString(attachment.jsonObject))
                    }
                    if (transaction.encryptedMessage == null && transaction.message == null) {
                        throw BurstException.NotValidException("Missing feedback message")
                    }
                    if (transaction.encryptedMessage != null && !transaction.encryptedMessage!!.isText) {
                        throw BurstException.NotValidException("Only text encrypted messages allowed")
                    }
                    if (transaction.message != null && !transaction.message!!.isText) {
                        throw BurstException.NotValidException("Only text public messages allowed")
                    }
                    if (purchase == null || purchase.encryptedGoods == null) {
                        throw BurstException.NotCurrentlyValidException("Purchase does not exist yet or not yet delivered")
                    }
                }

                override fun getDuplicationKey(transaction: Transaction): TransactionDuplicationKey {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsFeedback
                    return TransactionDuplicationKey(DigitalGoods.FEEDBACK, Convert.toUnsignedLong(attachment.purchaseId))
                }

                override fun hasRecipient(): Boolean {
                    return true
                }

            }

            val REFUND: TransactionType = object : DigitalGoods() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_DIGITAL_GOODS_REFUND

                override val description: String
                    get() = "Refund"

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.DigitalGoodsRefund {
                    return Attachment.DigitalGoodsRefund(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.DigitalGoodsRefund {
                    return Attachment.DigitalGoodsRefund(attachmentData)
                }

                override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
                    logger.trace("TransactionType REFUND")
                    val totalAmountNQT = calculateAttachmentTotalAmountNQT(transaction)
                    if (senderAccount.unconfirmedBalanceNQT >= totalAmountNQT) {
                        dp!!.accountService.addToUnconfirmedBalanceNQT(senderAccount, -totalAmountNQT)
                        return true
                    }
                    return false
                }

                public override fun calculateAttachmentTotalAmountNQT(transaction: Transaction): Long {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsRefund
                    return attachment.refundNQT
                }

                override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {
                    dp!!.accountService.addToUnconfirmedBalanceNQT(senderAccount, calculateAttachmentTotalAmountNQT(transaction))
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsRefund
                    dp!!.digitalGoodsStoreService.refund(transaction.senderId, attachment.purchaseId,
                            attachment.refundNQT, transaction.encryptedMessage)
                }

                @Throws(BurstException.ValidationException::class)
                override fun doValidateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsRefund
                    val purchase = dp!!.digitalGoodsStoreService.getPurchase(attachment.purchaseId)
                    if (attachment.refundNQT < 0 || attachment.refundNQT > Constants.MAX_BALANCE_NQT
                            || purchase != null && (purchase.buyerId != transaction.recipientId || transaction.senderId != purchase.sellerId)) {
                        throw BurstException.NotValidException("Invalid digital goods refund: " + JSON.toJsonString(attachment.jsonObject))
                    }
                    if (transaction.encryptedMessage != null && !transaction.encryptedMessage!!.isText) {
                        throw BurstException.NotValidException("Only text encrypted messages allowed")
                    }
                    if (purchase == null || purchase.encryptedGoods == null || purchase.refundNQT != 0L) {
                        throw BurstException.NotCurrentlyValidException("Purchase does not exist or is not delivered or is already refunded")
                    }
                }

                override fun getDuplicationKey(transaction: Transaction): TransactionDuplicationKey {
                    val attachment = transaction.attachment as Attachment.DigitalGoodsRefund
                    return TransactionDuplicationKey(DigitalGoods.REFUND, Convert.toUnsignedLong(attachment.purchaseId))
                }

                override fun hasRecipient(): Boolean {
                    return true
                }

            }
        }

    }

    abstract class AccountControl private constructor() : TransactionType() {

        override val type: Byte
            get() = TransactionType.TYPE_ACCOUNT_CONTROL

        override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
            return true
        }

        override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {}

        companion object {

            val EFFECTIVE_BALANCE_LEASING: TransactionType = object : AccountControl() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_ACCOUNT_CONTROL_EFFECTIVE_BALANCE_LEASING

                override val description: String
                    get() = "Effective Balance Leasing"

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.AccountControlEffectiveBalanceLeasing {
                    return Attachment.AccountControlEffectiveBalanceLeasing(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.AccountControlEffectiveBalanceLeasing {
                    return Attachment.AccountControlEffectiveBalanceLeasing(attachmentData)
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    // TODO: check if anyone's used this or if it's even possible to use this, and eliminate it if possible
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.AccountControlEffectiveBalanceLeasing
                    val recipientAccount = dp!!.accountService.getAccount(transaction.recipientId)
                    if (transaction.senderId == transaction.recipientId
                            || transaction.amountNQT != 0L
                            || attachment.period < 1440) {
                        throw BurstException.NotValidException("Invalid effective balance leasing: " + JSON.toJsonString(transaction.jsonObject) + " transaction " + transaction.stringId)
                    }
                    if (recipientAccount == null || recipientAccount.publicKey == null && transaction.stringId.get() != "5081403377391821646") {
                        throw BurstException.NotCurrentlyValidException("Invalid effective balance leasing: "
                                + " recipient account " + transaction.recipientId + " not found or no public key published")
                    }
                }

                override fun hasRecipient(): Boolean {
                    return true
                }

            }
        }

    }

    abstract class BurstMining private constructor() : TransactionType() {

        override val type: Byte
            get() = TransactionType.TYPE_BURST_MINING

        override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
            return true
        }

        override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {}

        companion object {

            val REWARD_RECIPIENT_ASSIGNMENT: TransactionType = object : BurstMining() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_BURST_MINING_REWARD_RECIPIENT_ASSIGNMENT

                override val description: String
                    get() = "Reward Recipient Assignment"

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.BurstMiningRewardRecipientAssignment {
                    return Attachment.BurstMiningRewardRecipientAssignment(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.BurstMiningRewardRecipientAssignment {
                    return Attachment.BurstMiningRewardRecipientAssignment(attachmentData)
                }

                internal override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account) {
                    dp!!.accountService.setRewardRecipientAssignment(senderAccount, recipientAccount.id)
                }

                override fun getDuplicationKey(transaction: Transaction): TransactionDuplicationKey {
                    return if (!dp!!.fluxCapacitor.getValue(FluxValues.DIGITAL_GOODS_STORE)) {
                        TransactionDuplicationKey.IS_NEVER_DUPLICATE // sync fails after 7007 without this
                    } else TransactionDuplicationKey(BurstMining.REWARD_RECIPIENT_ASSIGNMENT, Convert.toUnsignedLong(transaction.senderId))

                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    val height = dp!!.blockchain.lastBlock.height + 1
                    val sender = dp!!.accountService.getAccount(transaction.senderId)
                            ?: throw BurstException.NotCurrentlyValidException("Sender not yet known ?!")

                    val rewardAssignment = dp!!.accountService.getRewardRecipientAssignment(sender)
                    if (rewardAssignment != null && rewardAssignment.fromHeight >= height) {
                        throw BurstException.NotCurrentlyValidException("Cannot reassign reward recipient before previous goes into effect: " + JSON.toJsonString(transaction.jsonObject))
                    }
                    val recip = dp!!.accountService.getAccount(transaction.recipientId)
                    if (recip == null || recip.publicKey == null) {
                        throw BurstException.NotValidException("Reward recipient must have public key saved in dp.blockchain: " + JSON.toJsonString(transaction.jsonObject))
                    }

                    if (dp!!.fluxCapacitor.getValue(FluxValues.PRE_DYMAXION)) {
                        if (transaction.amountNQT != 0L || transaction.feeNQT < FEE_QUANT) {
                            throw BurstException.NotValidException("Reward recipient assignment transaction must have 0 send amount and at least minimum fee: " + JSON.toJsonString(transaction.jsonObject))
                        }
                    } else {
                        if (transaction.amountNQT != 0L || transaction.feeNQT != Constants.ONE_BURST) {
                            throw BurstException.NotValidException("Reward recipient assignment transaction must have 0 send amount and 1 fee: " + JSON.toJsonString(transaction.jsonObject))
                        }
                    }

                    if (!dp!!.fluxCapacitor.getValue(FluxValues.REWARD_RECIPIENT_ENABLE, height)) {
                        throw BurstException.NotCurrentlyValidException("Reward recipient assignment not allowed before block " + dp!!.fluxCapacitor.getStartingHeight(FluxValues.REWARD_RECIPIENT_ENABLE)!!)
                    }
                }

                override fun hasRecipient(): Boolean {
                    return true
                }
            }
        }
    }

    abstract class AdvancedPayment private constructor() : TransactionType() {

        override val type: Byte
            get() = TransactionType.TYPE_ADVANCED_PAYMENT

        companion object {

            val ESCROW_CREATION: TransactionType = object : AdvancedPayment() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_ADVANCED_PAYMENT_ESCROW_CREATION

                override val description: String
                    get() = "Escrow Creation"

                @Throws(BurstException.NotValidException::class)
                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.AdvancedPaymentEscrowCreation {
                    return Attachment.AdvancedPaymentEscrowCreation(buffer, transactionVersion)
                }

                @Throws(BurstException.NotValidException::class)
                override fun parseAttachment(attachmentData: JsonObject): Attachment.AdvancedPaymentEscrowCreation {
                    return Attachment.AdvancedPaymentEscrowCreation(attachmentData)
                }

                override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
                    logger.trace("TransactionType ESCROW_CREATION")
                    val totalAmountNQT = calculateAttachmentTotalAmountNQT(transaction)
                    if (senderAccount.unconfirmedBalanceNQT < totalAmountNQT) {
                        return false
                    }
                    dp!!.accountService.addToUnconfirmedBalanceNQT(senderAccount, -totalAmountNQT)
                    return true
                }

                public override fun calculateAttachmentTotalAmountNQT(transaction: Transaction): Long {
                    val attachment = transaction.attachment as Attachment.AdvancedPaymentEscrowCreation
                    return Convert.safeAdd(attachment.amountNQT!!, Convert.safeMultiply(attachment.totalSigners.toLong(), Constants.ONE_BURST))
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.AdvancedPaymentEscrowCreation
                    val totalAmountNQT = calculateAttachmentTotalAmountNQT(transaction)
                    dp!!.accountService.addToBalanceNQT(senderAccount, -totalAmountNQT)
                    val signers = attachment.getSigners()
                    signers.forEach { signer -> dp!!.accountService.addToBalanceAndUnconfirmedBalanceNQT(dp!!.accountService.getOrAddAccount(signer), Constants.ONE_BURST) }
                    dp!!.escrowService.addEscrowTransaction(senderAccount,
                            recipientAccount!!,
                            transaction.id,
                            attachment.amountNQT,
                            attachment.getRequiredSigners(),
                            attachment.getSigners(),
                            transaction.timestamp + attachment.deadline,
                            attachment.deadlineAction!!)
                }

                override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {
                    dp!!.accountService.addToUnconfirmedBalanceNQT(senderAccount, calculateAttachmentTotalAmountNQT(transaction))
                }

                override fun getDuplicationKey(transaction: Transaction): TransactionDuplicationKey {
                    return TransactionDuplicationKey.IS_NEVER_DUPLICATE
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.AdvancedPaymentEscrowCreation
                    var totalAmountNQT: Long? = Convert.safeAdd(attachment.amountNQT!!, transaction.feeNQT)
                    if (transaction.senderId == transaction.recipientId) {
                        throw BurstException.NotValidException("Escrow must have different sender and recipient")
                    }
                    totalAmountNQT = Convert.safeAdd(totalAmountNQT!!, attachment.totalSigners * Constants.ONE_BURST)
                    if (transaction.amountNQT != 0L) {
                        throw BurstException.NotValidException("Transaction sent amount must be 0 for escrow")
                    }
                    if (totalAmountNQT.compareTo(0L) < 0 || totalAmountNQT.compareTo(Constants.MAX_BALANCE_NQT) > 0) {
                        throw BurstException.NotValidException("Invalid escrow creation amount")
                    }
                    if (transaction.feeNQT < Constants.ONE_BURST) {
                        throw BurstException.NotValidException("Escrow transaction must have a fee at least 1 burst")
                    }
                    if (attachment.getRequiredSigners() < 1 || attachment.getRequiredSigners() > 10) {
                        throw BurstException.NotValidException("Escrow required signers much be 1 - 10")
                    }
                    if (attachment.getRequiredSigners() > attachment.totalSigners) {
                        throw BurstException.NotValidException("Cannot have more required than signers on escrow")
                    }
                    if (attachment.totalSigners < 1 || attachment.totalSigners > 10) {
                        throw BurstException.NotValidException("Escrow transaction requires 1 - 10 signers")
                    }
                    if (attachment.deadline < 1 || attachment.deadline > 7776000) { // max deadline 3 months
                        throw BurstException.NotValidException("Escrow deadline must be 1 - 7776000 seconds")
                    }
                    if (attachment.deadlineAction == null || attachment.deadlineAction == Escrow.DecisionType.UNDECIDED) {
                        throw BurstException.NotValidException("Invalid deadline action for escrow")
                    }
                    if (attachment.getSigners().contains(transaction.senderId) || attachment.getSigners().contains(transaction.recipientId)) {
                        throw BurstException.NotValidException("Escrow sender and recipient cannot be signers")
                    }
                    if (!dp!!.escrowService.isEnabled) {
                        throw BurstException.NotYetEnabledException("Escrow not yet enabled")
                    }
                }

                override fun hasRecipient(): Boolean {
                    return true
                }
            }

            val ESCROW_SIGN: TransactionType = object : AdvancedPayment() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_ADVANCED_PAYMENT_ESCROW_SIGN

                override val description: String
                    get() = "Escrow Sign"

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.AdvancedPaymentEscrowSign {
                    return Attachment.AdvancedPaymentEscrowSign(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.AdvancedPaymentEscrowSign {
                    return Attachment.AdvancedPaymentEscrowSign(attachmentData)
                }

                override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
                    return true
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.AdvancedPaymentEscrowSign
                    val escrow = dp!!.escrowService.getEscrowTransaction(attachment.escrowId)
                    dp!!.escrowService.sign(senderAccount.id, attachment.decision!!, escrow)
                }

                override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {
                    // Nothing to undo.
                }

                override fun getDuplicationKey(transaction: Transaction): TransactionDuplicationKey {
                    val attachment = transaction.attachment as Attachment.AdvancedPaymentEscrowSign
                    val uniqueString = Convert.toUnsignedLong(attachment.escrowId!!) + ":" +
                            Convert.toUnsignedLong(transaction.senderId)
                    return TransactionDuplicationKey(AdvancedPayment.ESCROW_SIGN, uniqueString)
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.AdvancedPaymentEscrowSign
                    if (transaction.amountNQT != 0L || transaction.feeNQT != Constants.ONE_BURST) {
                        throw BurstException.NotValidException("Escrow signing must have amount 0 and fee of 1")
                    }
                    if (attachment.escrowId == null || attachment.decision == null) {
                        throw BurstException.NotValidException("Escrow signing requires escrow id and decision set")
                    }
                    val escrow = dp!!.escrowService.getEscrowTransaction(attachment.escrowId)
                            ?: throw BurstException.NotValidException("Escrow transaction not found")
                    if (!dp!!.escrowService.isIdSigner(transaction.senderId, escrow) &&
                            escrow.senderId != transaction.senderId &&
                            escrow.recipientId != transaction.senderId) {
                        throw BurstException.NotValidException("Sender is not a participant in specified escrow")
                    }
                    if (escrow.senderId == transaction.senderId && attachment.decision != Escrow.DecisionType.RELEASE) {
                        throw BurstException.NotValidException("Escrow sender can only release")
                    }
                    if (escrow.recipientId == transaction.senderId && attachment.decision != Escrow.DecisionType.REFUND) {
                        throw BurstException.NotValidException("Escrow recipient can only refund")
                    }
                    if (!dp!!.escrowService.isEnabled) {
                        throw BurstException.NotYetEnabledException("Escrow not yet enabled")
                    }
                }

                override fun hasRecipient(): Boolean {
                    return false
                }
            }

            val ESCROW_RESULT: TransactionType = object : AdvancedPayment() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_ADVANCED_PAYMENT_ESCROW_RESULT

                override val description: String
                    get() = "Escrow Result"

                override val isSigned: Boolean
                    get() = false

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.AdvancedPaymentEscrowResult {
                    return Attachment.AdvancedPaymentEscrowResult(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.AdvancedPaymentEscrowResult {
                    return Attachment.AdvancedPaymentEscrowResult(attachmentData)
                }

                override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
                    return false
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    // Nothing to apply.
                }

                override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {
                    // Nothing to undo.
                }

                override fun getDuplicationKey(transaction: Transaction): TransactionDuplicationKey {
                    return TransactionDuplicationKey.IS_ALWAYS_DUPLICATE
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    throw BurstException.NotValidException("Escrow result never validates")
                }

                override fun hasRecipient(): Boolean {
                    return true
                }
            }

            val SUBSCRIPTION_SUBSCRIBE: TransactionType = object : AdvancedPayment() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_ADVANCED_PAYMENT_SUBSCRIPTION_SUBSCRIBE

                override val description: String
                    get() = "Subscription Subscribe"

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.AdvancedPaymentSubscriptionSubscribe {
                    return Attachment.AdvancedPaymentSubscriptionSubscribe(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.AdvancedPaymentSubscriptionSubscribe {
                    return Attachment.AdvancedPaymentSubscriptionSubscribe(attachmentData)
                }

                override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
                    return true
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.AdvancedPaymentSubscriptionSubscribe
                    dp!!.subscriptionService.addSubscription(senderAccount, recipientAccount!!, transaction.id, transaction.amountNQT, transaction.timestamp, attachment.frequency!!)
                }

                override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {
                    // Nothing to undo.
                }

                override fun getDuplicationKey(transaction: Transaction): TransactionDuplicationKey {
                    return TransactionDuplicationKey.IS_NEVER_DUPLICATE
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.AdvancedPaymentSubscriptionSubscribe
                    if (attachment.frequency == null ||
                            attachment.frequency < Constants.BURST_SUBSCRIPTION_MIN_FREQ ||
                            attachment.frequency > Constants.BURST_SUBSCRIPTION_MAX_FREQ) {
                        throw BurstException.NotValidException("Invalid subscription frequency")
                    }
                    if (transaction.amountNQT < Constants.ONE_BURST || transaction.amountNQT > Constants.MAX_BALANCE_NQT) {
                        throw BurstException.NotValidException("Subscriptions must be at least one burst")
                    }
                    if (transaction.senderId == transaction.recipientId) {
                        throw BurstException.NotValidException("Cannot create subscription to same address")
                    }
                    if (!dp!!.subscriptionService.isEnabled) {
                        throw BurstException.NotYetEnabledException("Subscriptions not yet enabled")
                    }
                }

                override fun hasRecipient(): Boolean {
                    return true
                }
            }

            val SUBSCRIPTION_CANCEL: TransactionType = object : AdvancedPayment() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_ADVANCED_PAYMENT_SUBSCRIPTION_CANCEL

                override val description: String
                    get() = "Subscription Cancel"

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.AdvancedPaymentSubscriptionCancel {
                    return Attachment.AdvancedPaymentSubscriptionCancel(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.AdvancedPaymentSubscriptionCancel {
                    return Attachment.AdvancedPaymentSubscriptionCancel(attachmentData)
                }

                override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
                    logger.trace("TransactionType SUBSCRIPTION_CANCEL")
                    val attachment = transaction.attachment as Attachment.AdvancedPaymentSubscriptionCancel
                    dp!!.subscriptionService.addRemoval(attachment.subscriptionId)
                    return true
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.AdvancedPaymentSubscriptionCancel
                    dp!!.subscriptionService.removeSubscription(attachment.subscriptionId)
                }

                override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {
                    // Nothing to undo.
                }

                override fun getDuplicationKey(transaction: Transaction): TransactionDuplicationKey {
                    val attachment = transaction.attachment as Attachment.AdvancedPaymentSubscriptionCancel
                    return TransactionDuplicationKey(AdvancedPayment.SUBSCRIPTION_CANCEL, Convert.toUnsignedLong(attachment.subscriptionId!!))
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    val attachment = transaction.attachment as Attachment.AdvancedPaymentSubscriptionCancel
                    if (attachment.subscriptionId == null) {
                        throw BurstException.NotValidException("Subscription cancel must include subscription id")
                    }

                    val subscription = dp!!.subscriptionService.getSubscription(attachment.subscriptionId)
                            ?: throw BurstException.NotValidException("Subscription cancel must contain current subscription id")

                    if (subscription.senderId != transaction.senderId && subscription.recipientId != transaction.senderId) {
                        throw BurstException.NotValidException("Subscription cancel can only be done by participants")
                    }

                    if (!dp!!.subscriptionService.isEnabled) {
                        throw BurstException.NotYetEnabledException("Subscription cancel not yet enabled")
                    }
                }

                override fun hasRecipient(): Boolean {
                    return false
                }
            }

            val SUBSCRIPTION_PAYMENT: TransactionType = object : AdvancedPayment() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_ADVANCED_PAYMENT_SUBSCRIPTION_PAYMENT

                override val description: String
                    get() = "Subscription Payment"

                override val isSigned: Boolean
                    get() = false

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): Attachment.AdvancedPaymentSubscriptionPayment {
                    return Attachment.AdvancedPaymentSubscriptionPayment(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): Attachment.AdvancedPaymentSubscriptionPayment {
                    return Attachment.AdvancedPaymentSubscriptionPayment(attachmentData)
                }

                override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
                    return false
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    // Nothing to apply.
                }

                override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {
                    // Nothing to undo.
                }

                override fun getDuplicationKey(transaction: Transaction): TransactionDuplicationKey {
                    return TransactionDuplicationKey.IS_ALWAYS_DUPLICATE
                }

                @Throws(BurstException.ValidationException::class)
                override fun validateAttachment(transaction: Transaction) {
                    throw BurstException.NotValidException("Subscription payment never validates")
                }

                override fun hasRecipient(): Boolean {
                    return true
                }
            }
        }
    }

    abstract class AutomatedTransactions private constructor() : TransactionType() {

        override val type: Byte
            get() = TransactionType.TYPE_AUTOMATED_TRANSACTIONS

        override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account): Boolean {
            return true
        }

        override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) {

        }

        @Throws(BurstException.ValidationException::class)
        override fun validateAttachment(transaction: Transaction) {
            if (transaction.amountNQT != 0L) {
                throw BurstException.NotValidException("Invalid automated transaction transaction")
            }
            doValidateAttachment(transaction)
        }

        @Throws(BurstException.ValidationException::class)
        internal abstract fun doValidateAttachment(transaction: Transaction)

        companion object {


            val AUTOMATED_TRANSACTION_CREATION: TransactionType = object : AutomatedTransactions() {

                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_AT_CREATION

                override val description: String
                    get() = "AT Creation"

                @Throws(NotValidException::class)
                override fun parseAttachment(buffer: ByteBuffer,
                                             transactionVersion: Byte): AbstractAttachment {
                    return AutomatedTransactionsCreation(buffer, transactionVersion)
                }

                override fun parseAttachment(attachmentData: JsonObject): AbstractAttachment {
                    return AutomatedTransactionsCreation(attachmentData)
                }

                @Throws(ValidationException::class)
                override fun doValidateAttachment(transaction: Transaction) {
                    if (!dp!!.fluxCapacitor.getValue(FluxValues.AUTOMATED_TRANSACTION_BLOCK, dp!!.blockchain.lastBlock.height)) {
                        throw BurstException.NotYetEnabledException("Automated Transactions not yet enabled at height " + dp!!.blockchain.lastBlock.height)
                    }
                    if (transaction.signature != null && dp!!.accountService.getAccount(transaction.id) != null) {
                        val existingAccount = dp!!.accountService.getAccount(transaction.id)
                        if (existingAccount.publicKey != null && !Arrays.equals(existingAccount.publicKey, ByteArray(32)))
                            throw BurstException.NotValidException("Account with id already exists")
                    }
                    val attachment = transaction.attachment as Attachment.AutomatedTransactionsCreation
                    val totalPages: Long
                    try {
                        totalPages = AtController.checkCreationBytes(attachment.creationBytes, dp!!.blockchain.height).toLong()
                    } catch (e: AtException) {
                        throw BurstException.NotCurrentlyValidException("Invalid AT creation bytes", e)
                    }

                    val requiredFee = totalPages * AtConstants.costPerPage(transaction.height)
                    if (transaction.feeNQT < requiredFee) {
                        throw BurstException.NotValidException("Insufficient fee for AT creation. Minimum: " + Convert.toUnsignedLong(requiredFee / Constants.ONE_BURST))
                    }
                    if (dp!!.fluxCapacitor.getValue(FluxValues.AT_FIX_BLOCK_3)) {
                        if (attachment.name!!.length > Constants.MAX_AUTOMATED_TRANSACTION_NAME_LENGTH) {
                            throw BurstException.NotValidException("Name of automated transaction over size limit")
                        }
                        if (attachment.description!!.length > Constants.MAX_AUTOMATED_TRANSACTION_DESCRIPTION_LENGTH) {
                            throw BurstException.NotValidException("Description of automated transaction over size limit")
                        }
                    }
                }

                override fun applyAttachment(transaction: Transaction, senderAccount: Account, recipientAccount: Account?) {
                    val attachment = transaction.attachment as Attachment.AutomatedTransactionsCreation
                    AT.addAT(dp, transaction.id, transaction.senderId, attachment.name, attachment.description, attachment.creationBytes, transaction.height)
                }


                override fun hasRecipient(): Boolean {
                    return false
                }
            }

            val AT_PAYMENT: TransactionType = object : AutomatedTransactions() {
                override val subtype: Byte
                    get() = TransactionType.SUBTYPE_AT_NXT_PAYMENT

                override val description: String
                    get() = "AT Payment"

                override val isSigned: Boolean
                    get() = false

                override fun parseAttachment(buffer: ByteBuffer, transactionVersion: Byte): AbstractAttachment {
                    return Attachment.AT_PAYMENT
                }

                override fun parseAttachment(attachmentData: JsonObject): AbstractAttachment {
                    return Attachment.AT_PAYMENT
                }

                @Throws(BurstException.ValidationException::class)
                override fun doValidateAttachment(transaction: Transaction) {
                    throw BurstException.NotValidException("AT payment never validates")
                }

                override fun applyAttachment(transaction: Transaction,
                                             senderAccount: Account, recipientAccount: Account?) {
                    // Nothing to apply
                }


                override fun hasRecipient(): Boolean {
                    return true
                }
            }
        }

    }

    fun minimumFeeNQT(height: Int, appendagesSize: Int): Long {
        if (height < BASELINE_FEE_HEIGHT) {
            return 0 // No need to validate fees before baseline block
        }
        val fee = getBaselineFee(height)
        return Convert.safeAdd(fee.constantFee, Convert.safeMultiply(appendagesSize.toLong(), fee.appendagesFee))
    }

    protected open fun getBaselineFee(height: Int): Fee {
        return Fee(if (dp!!.fluxCapacitor.getValue(FluxValues.PRE_DYMAXION, height)) FEE_QUANT else ONE_BURST, 0)
    }

    class Fee internal constructor(internal val constantFee: Long, internal val appendagesFee: Long) {

        override fun toString(): String {
            return "Fee{" +
                    "constantFee=" + constantFee +
                    ", appendagesFee=" + appendagesFee +
                    '}'.toString()
        }
    }

    companion object {

        private val logger = LoggerFactory.getLogger(TransactionType::class.java)

        private val TRANSACTION_TYPES = mutableMapOf<Byte, Map<Byte, TransactionType>>>()

        private val TYPE_PAYMENT: Byte = 0
        private val TYPE_MESSAGING: Byte = 1
        private val TYPE_COLORED_COINS: Byte = 2
        private val TYPE_DIGITAL_GOODS: Byte = 3
        private val TYPE_ACCOUNT_CONTROL: Byte = 4
        private val TYPE_BURST_MINING: Byte = 20 // jump some for easier nxt updating
        private val TYPE_ADVANCED_PAYMENT: Byte = 21
        private val TYPE_AUTOMATED_TRANSACTIONS: Byte = 22

        private val SUBTYPE_PAYMENT_ORDINARY_PAYMENT: Byte = 0
        private val SUBTYPE_PAYMENT_ORDINARY_PAYMENT_MULTI_OUT: Byte = 1
        private val SUBTYPE_PAYMENT_ORDINARY_PAYMENT_MULTI_SAME_OUT: Byte = 2

        private val SUBTYPE_MESSAGING_ARBITRARY_MESSAGE: Byte = 0
        private val SUBTYPE_MESSAGING_ALIAS_ASSIGNMENT: Byte = 1
        private val SUBTYPE_MESSAGING_ACCOUNT_INFO: Byte = 5
        private val SUBTYPE_MESSAGING_ALIAS_SELL: Byte = 6
        private val SUBTYPE_MESSAGING_ALIAS_BUY: Byte = 7

        private val SUBTYPE_COLORED_COINS_ASSET_ISSUANCE: Byte = 0
        private val SUBTYPE_COLORED_COINS_ASSET_TRANSFER: Byte = 1
        private val SUBTYPE_COLORED_COINS_ASK_ORDER_PLACEMENT: Byte = 2
        private val SUBTYPE_COLORED_COINS_BID_ORDER_PLACEMENT: Byte = 3
        private val SUBTYPE_COLORED_COINS_ASK_ORDER_CANCELLATION: Byte = 4
        private val SUBTYPE_COLORED_COINS_BID_ORDER_CANCELLATION: Byte = 5

        private val SUBTYPE_DIGITAL_GOODS_LISTING: Byte = 0
        private val SUBTYPE_DIGITAL_GOODS_DELISTING: Byte = 1
        private val SUBTYPE_DIGITAL_GOODS_PRICE_CHANGE: Byte = 2
        private val SUBTYPE_DIGITAL_GOODS_QUANTITY_CHANGE: Byte = 3
        private val SUBTYPE_DIGITAL_GOODS_PURCHASE: Byte = 4
        private val SUBTYPE_DIGITAL_GOODS_DELIVERY: Byte = 5
        private val SUBTYPE_DIGITAL_GOODS_FEEDBACK: Byte = 6
        private val SUBTYPE_DIGITAL_GOODS_REFUND: Byte = 7

        private val SUBTYPE_AT_CREATION: Byte = 0
        private val SUBTYPE_AT_NXT_PAYMENT: Byte = 1

        private val SUBTYPE_ACCOUNT_CONTROL_EFFECTIVE_BALANCE_LEASING: Byte = 0

        private val SUBTYPE_BURST_MINING_REWARD_RECIPIENT_ASSIGNMENT: Byte = 0

        private val SUBTYPE_ADVANCED_PAYMENT_ESCROW_CREATION: Byte = 0
        private val SUBTYPE_ADVANCED_PAYMENT_ESCROW_SIGN: Byte = 1
        private val SUBTYPE_ADVANCED_PAYMENT_ESCROW_RESULT: Byte = 2
        private val SUBTYPE_ADVANCED_PAYMENT_SUBSCRIPTION_SUBSCRIBE: Byte = 3
        private val SUBTYPE_ADVANCED_PAYMENT_SUBSCRIPTION_CANCEL: Byte = 4
        private val SUBTYPE_ADVANCED_PAYMENT_SUBSCRIPTION_PAYMENT: Byte = 5

        private val BASELINE_FEE_HEIGHT = 1 // At release time must be less than current block - 1440
        private val BASELINE_ASSET_ISSUANCE_FEE = Fee(Constants.ASSET_ISSUANCE_FEE_NQT, 0)

        // TODO don't store a static instance!
        private lateinit var dp: DependencyProvider

        // TODO Temporary...
        fun init(dp: DependencyProvider) {
            TransactionType.dp = dp

            val paymentTypes = mutableMapOf<Byte, TransactionType>>()
            paymentTypes[SUBTYPE_PAYMENT_ORDINARY_PAYMENT] = Payment.ORDINARY
            paymentTypes[SUBTYPE_PAYMENT_ORDINARY_PAYMENT_MULTI_OUT] = Payment.MULTI_OUT
            paymentTypes[SUBTYPE_PAYMENT_ORDINARY_PAYMENT_MULTI_SAME_OUT] = Payment.MULTI_SAME_OUT

            val messagingTypes = mutableMapOf<Byte, TransactionType>>()
            messagingTypes[SUBTYPE_MESSAGING_ARBITRARY_MESSAGE] = Messaging.ARBITRARY_MESSAGE
            messagingTypes[SUBTYPE_MESSAGING_ALIAS_ASSIGNMENT] = Messaging.ALIAS_ASSIGNMENT
            messagingTypes[SUBTYPE_MESSAGING_ACCOUNT_INFO] = Messaging.ACCOUNT_INFO
            messagingTypes[SUBTYPE_MESSAGING_ALIAS_BUY] = Messaging.ALIAS_BUY
            messagingTypes[SUBTYPE_MESSAGING_ALIAS_SELL] = Messaging.ALIAS_SELL

            val coloredCoinsTypes = mutableMapOf<Byte, TransactionType>>()
            coloredCoinsTypes[SUBTYPE_COLORED_COINS_ASSET_ISSUANCE] = ColoredCoins.ASSET_ISSUANCE
            coloredCoinsTypes[SUBTYPE_COLORED_COINS_ASSET_TRANSFER] = ColoredCoins.ASSET_TRANSFER
            coloredCoinsTypes[SUBTYPE_COLORED_COINS_ASK_ORDER_PLACEMENT] = ColoredCoins.ASK_ORDER_PLACEMENT
            coloredCoinsTypes[SUBTYPE_COLORED_COINS_BID_ORDER_PLACEMENT] = ColoredCoins.BID_ORDER_PLACEMENT
            coloredCoinsTypes[SUBTYPE_COLORED_COINS_ASK_ORDER_CANCELLATION] = ColoredCoins.ASK_ORDER_CANCELLATION
            coloredCoinsTypes[SUBTYPE_COLORED_COINS_BID_ORDER_CANCELLATION] = ColoredCoins.BID_ORDER_CANCELLATION

            val digitalGoodsTypes = mutableMapOf<Byte, TransactionType>>()
            digitalGoodsTypes[SUBTYPE_DIGITAL_GOODS_LISTING] = DigitalGoods.LISTING
            digitalGoodsTypes[SUBTYPE_DIGITAL_GOODS_DELISTING] = DigitalGoods.DELISTING
            digitalGoodsTypes[SUBTYPE_DIGITAL_GOODS_PRICE_CHANGE] = DigitalGoods.PRICE_CHANGE
            digitalGoodsTypes[SUBTYPE_DIGITAL_GOODS_QUANTITY_CHANGE] = DigitalGoods.QUANTITY_CHANGE
            digitalGoodsTypes[SUBTYPE_DIGITAL_GOODS_PURCHASE] = DigitalGoods.PURCHASE
            digitalGoodsTypes[SUBTYPE_DIGITAL_GOODS_DELIVERY] = DigitalGoods.DELIVERY
            digitalGoodsTypes[SUBTYPE_DIGITAL_GOODS_FEEDBACK] = DigitalGoods.FEEDBACK
            digitalGoodsTypes[SUBTYPE_DIGITAL_GOODS_REFUND] = DigitalGoods.REFUND

            val atTypes = mutableMapOf<Byte, TransactionType>>()
            atTypes[SUBTYPE_AT_CREATION] = AutomatedTransactions.AUTOMATED_TRANSACTION_CREATION
            atTypes[SUBTYPE_AT_NXT_PAYMENT] = AutomatedTransactions.AT_PAYMENT

            val accountControlTypes = mutableMapOf<Byte, TransactionType>>()
            accountControlTypes[SUBTYPE_ACCOUNT_CONTROL_EFFECTIVE_BALANCE_LEASING] = AccountControl.EFFECTIVE_BALANCE_LEASING

            val burstMiningTypes = mutableMapOf<Byte, TransactionType>>()
            burstMiningTypes[SUBTYPE_BURST_MINING_REWARD_RECIPIENT_ASSIGNMENT] = BurstMining.REWARD_RECIPIENT_ASSIGNMENT

            val advancedPaymentTypes = mutableMapOf<Byte, TransactionType>>()
            advancedPaymentTypes[SUBTYPE_ADVANCED_PAYMENT_ESCROW_CREATION] = AdvancedPayment.ESCROW_CREATION
            advancedPaymentTypes[SUBTYPE_ADVANCED_PAYMENT_ESCROW_SIGN] = AdvancedPayment.ESCROW_SIGN
            advancedPaymentTypes[SUBTYPE_ADVANCED_PAYMENT_ESCROW_RESULT] = AdvancedPayment.ESCROW_RESULT
            advancedPaymentTypes[SUBTYPE_ADVANCED_PAYMENT_SUBSCRIPTION_SUBSCRIBE] = AdvancedPayment.SUBSCRIPTION_SUBSCRIBE
            advancedPaymentTypes[SUBTYPE_ADVANCED_PAYMENT_SUBSCRIPTION_CANCEL] = AdvancedPayment.SUBSCRIPTION_CANCEL
            advancedPaymentTypes[SUBTYPE_ADVANCED_PAYMENT_SUBSCRIPTION_PAYMENT] = AdvancedPayment.SUBSCRIPTION_PAYMENT

            TRANSACTION_TYPES[TYPE_PAYMENT] = Collections.unmodifiableMap(paymentTypes)
            TRANSACTION_TYPES[TYPE_MESSAGING] = Collections.unmodifiableMap(messagingTypes)
            TRANSACTION_TYPES[TYPE_COLORED_COINS] = Collections.unmodifiableMap(coloredCoinsTypes)
            TRANSACTION_TYPES[TYPE_DIGITAL_GOODS] = Collections.unmodifiableMap(digitalGoodsTypes)
            TRANSACTION_TYPES[TYPE_ACCOUNT_CONTROL] = Collections.unmodifiableMap(accountControlTypes)
            TRANSACTION_TYPES[TYPE_BURST_MINING] = Collections.unmodifiableMap(burstMiningTypes)
            TRANSACTION_TYPES[TYPE_ADVANCED_PAYMENT] = Collections.unmodifiableMap(advancedPaymentTypes)
            TRANSACTION_TYPES[TYPE_AUTOMATED_TRANSACTIONS] = Collections.unmodifiableMap(atTypes)
        }

        fun findTransactionType(type: Byte, subtype: Byte): TransactionType? {
            val subtypes = TRANSACTION_TYPES[type]
            return if (subtypes == null) null else subtypes[subtype]
        }

        fun getTypeDescription(type: Byte): String {
            when (type) {
                TYPE_PAYMENT -> return "Payment"
                TYPE_MESSAGING -> return "Messaging"
                TYPE_COLORED_COINS -> return "Colored coins"
                TYPE_DIGITAL_GOODS -> return "Digital Goods"
                TYPE_ACCOUNT_CONTROL -> return "Account Control"
                TYPE_BURST_MINING -> return "Burst Mining"
                TYPE_ADVANCED_PAYMENT -> return "Advanced Payment"
                TYPE_AUTOMATED_TRANSACTIONS -> return "Automated Transactions"
                else -> return "Unknown"
            }
        }

        val transactionTypes: Map<Byte, Map<Byte, TransactionType>>
            get() = Collections.unmodifiableMap(TRANSACTION_TYPES)
    }

}
