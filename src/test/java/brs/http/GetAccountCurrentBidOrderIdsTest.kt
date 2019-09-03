package brs.http

import brs.Account
import brs.BurstException
import brs.Order.Bid
import brs.assetexchange.AssetExchange
import brs.common.AbstractUnitTest
import brs.common.QuickMocker
import brs.common.QuickMocker.MockParam
import brs.services.ParameterService
import brs.util.JSON
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Before
import org.junit.Test

import javax.servlet.http.HttpServletRequest
import brs.http.common.ResultFields.BID_ORDER_IDS_RESPONSE
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

class GetAccountCurrentBidOrderIdsTest : AbstractUnitTest() {

    private var t: GetAccountCurrentBidOrderIds? = null

    private var mockParameterService: ParameterService? = null
    private var mockAssetExchange: AssetExchange? = null

    @Before
    fun setUp() {
        mockParameterService = mock<ParameterService>()
        mockAssetExchange = mock<AssetExchange>()

        t = GetAccountCurrentBidOrderIds(mockParameterService!!, mockAssetExchange!!)
    }

    @Test
    @Throws(BurstException::class)
    fun processRequest_byAccount() {
        val accountId = 123L
        val firstIndex = 0
        val lastIndex = 1

        val req = QuickMocker.httpServletRequest(
                MockParam(ACCOUNT_PARAMETER, accountId),
                MockParam(FIRST_INDEX_PARAMETER, firstIndex),
                MockParam(LAST_INDEX_PARAMETER, lastIndex)
        )

        val mockAccount = mock<Account>()
        whenever(mockAccount.id).doReturn(accountId)

        val mockBidId = 456L
        val bid = mock<Bid>()
        whenever(bid.id).doReturn(mockBidId)

        val mockBidIterator = mockCollection<Bid>(bid)

        whenever(mockParameterService!!.getAccount(eq<HttpServletRequest>(req))).doReturn(mockAccount)
        whenever(mockAssetExchange!!.getBidOrdersByAccount(eq(accountId), eq(firstIndex), eq(lastIndex))).doReturn(mockBidIterator)

        val result = t!!.processRequest(req) as JsonObject
        assertNotNull(result)

        val resultList = result.get(BID_ORDER_IDS_RESPONSE) as JsonArray
        assertNotNull(resultList)
        assertEquals(1, resultList.size().toLong())
        assertEquals("" + mockBidId, JSON.getAsString(resultList.get(0)))
    }

    @Test
    @Throws(BurstException::class)
    fun processRequest_byAccountAsset() {
        val accountId = 123L
        val assetId = 234L
        val firstIndex = 0
        val lastIndex = 1

        val req = QuickMocker.httpServletRequest(
                MockParam(ACCOUNT_PARAMETER, accountId),
                MockParam(ASSET_PARAMETER, assetId),
                MockParam(FIRST_INDEX_PARAMETER, firstIndex),
                MockParam(LAST_INDEX_PARAMETER, lastIndex)
        )

        val mockAccount = mock<Account>()
        whenever(mockAccount.id).doReturn(accountId)

        val mockBidId = 456L
        val bid = mock<Bid>()
        whenever(bid.id).doReturn(mockBidId)

        val mockBidIterator = mockCollection<Bid>(bid)

        whenever(mockParameterService!!.getAccount(eq<HttpServletRequest>(req))).doReturn(mockAccount)
        whenever(mockAssetExchange!!.getBidOrdersByAccountAsset(eq(accountId), eq(assetId), eq(firstIndex), eq(lastIndex))).doReturn(mockBidIterator)

        val result = t!!.processRequest(req) as JsonObject
        assertNotNull(result)

        val resultList = result.get(BID_ORDER_IDS_RESPONSE) as JsonArray
        assertNotNull(resultList)
        assertEquals(1, resultList.size().toLong())
        assertEquals("" + mockBidId, JSON.getAsString(resultList.get(0)))
    }

}
