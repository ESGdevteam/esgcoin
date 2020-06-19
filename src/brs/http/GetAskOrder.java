package brs.http;

import brs.AmzException;
import brs.Order;
import brs.assetexchange.AssetExchange;
import com.google.gson.JsonElement;

import javax.servlet.http.HttpServletRequest;

import static brs.http.JSONResponses.UNKNOWN_ORDER;
import static brs.http.common.Parameters.ORDER_PARAMETER;

public final class GetAskOrder extends APIServlet.JsonRequestHandler {

  private final AssetExchange assetExchange;

  GetAskOrder(AssetExchange assetExchange) {
    super(new APITag[] {APITag.AE}, ORDER_PARAMETER);
    this.assetExchange = assetExchange;
  }

  @Override
  JsonElement processRequest(HttpServletRequest req) throws AmzException {
    long orderId = ParameterParser.getOrderId(req);
    Order.Ask askOrder = assetExchange.getAskOrder(orderId);
    if (askOrder == null) {
      return UNKNOWN_ORDER;
    }
    return JSONData.askOrder(askOrder);
  }

}
