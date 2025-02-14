package brs.http;

import brs.EsgException;
import brs.services.AccountService;
import brs.services.ParameterService;
import com.google.gson.JsonElement;

import javax.servlet.http.HttpServletRequest;

import static brs.http.common.Parameters.AT_PARAMETER;

final class GetAT extends APIServlet.JsonRequestHandler {

  private final ParameterService parameterService;
  private final AccountService accountService;

  GetAT(ParameterService parameterService, AccountService accountService) {
    super(new APITag[]{APITag.AT}, AT_PARAMETER);
    this.parameterService = parameterService;
    this.accountService = accountService;
  }

  @Override
  JsonElement processRequest(HttpServletRequest req) throws EsgException {
    return JSONData.at(parameterService.getAT(req), accountService);
  }

}
