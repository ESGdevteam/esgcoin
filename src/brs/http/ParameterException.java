package brs.http;

import brs.AmzException;
import com.google.gson.JsonElement;

public final class ParameterException extends AmzException {

  private transient final JsonElement errorResponse;

  public ParameterException(JsonElement errorResponse) {
    this.errorResponse = errorResponse;
  }

  JsonElement getErrorResponse() {
    return errorResponse;
  }

}
