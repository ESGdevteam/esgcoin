package brs.http;

import brs.EsgException;
import com.google.gson.JsonElement;

public final class ParameterException extends EsgException {

  private transient final JsonElement errorResponse;

  public ParameterException(JsonElement errorResponse) {
    this.errorResponse = errorResponse;
  }

  JsonElement getErrorResponse() {
    return errorResponse;
  }

}
