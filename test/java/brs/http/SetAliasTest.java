package brs.http;

import brs.Attachment;
import brs.Blockchain;
import brs.Esg;
import brs.EsgException;
import brs.common.QuickMocker;
import brs.common.QuickMocker.MockParam;
import brs.fluxcapacitor.FluxCapacitor;
import brs.fluxcapacitor.FluxValues;
import brs.services.AliasService;
import brs.services.ParameterService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import javax.servlet.http.HttpServletRequest;

import static brs.TransactionType.Messaging.ALIAS_ASSIGNMENT;
import static brs.http.JSONResponses.*;
import static brs.http.common.Parameters.ALIAS_NAME_PARAMETER;
import static brs.http.common.Parameters.ALIAS_URI_PARAMETER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

@RunWith(PowerMockRunner.class)
@PrepareForTest(Esg.class)
public class SetAliasTest extends AbstractTransactionTest {

  private SetAlias t;

  private ParameterService parameterServiceMock;
  private Blockchain blockchainMock;
  private AliasService aliasServiceMock;
  private APITransactionManager apiTransactionManagerMock;

  @Before
  public void setUp() {
    parameterServiceMock = mock(ParameterService.class);
    blockchainMock = mock(Blockchain.class);
    aliasServiceMock = mock(AliasService.class);
    apiTransactionManagerMock = mock(APITransactionManager.class);

    t = new SetAlias(parameterServiceMock, blockchainMock, aliasServiceMock, apiTransactionManagerMock);
  }

  @Test
  public void processRequest() throws EsgException {
    final String aliasNameParameter = "aliasNameParameter";
    final String aliasUrl = "aliasUrl";

    final HttpServletRequest req = QuickMocker.httpServletRequest(
        new MockParam(ALIAS_NAME_PARAMETER, aliasNameParameter),
        new MockParam(ALIAS_URI_PARAMETER, aliasUrl)
    );

    mockStatic(Esg.class);
    final FluxCapacitor fluxCapacitor = QuickMocker.fluxCapacitorEnabledFunctionalities(FluxValues.DIGITAL_GOODS_STORE);
    when(Esg.getFluxCapacitor()).thenReturn(fluxCapacitor);

    final Attachment.MessagingAliasAssignment attachment = (Attachment.MessagingAliasAssignment) attachmentCreatedTransaction(() -> t.processRequest(req), apiTransactionManagerMock);
    assertNotNull(attachment);

    assertEquals(ALIAS_ASSIGNMENT, attachment.getTransactionType());
    assertEquals(aliasNameParameter, attachment.getAliasName());
    assertEquals(aliasUrl, attachment.getAliasURI());
  }

  @Test
  public void processRequest_missingAliasName() throws EsgException {
    final HttpServletRequest req = QuickMocker.httpServletRequest(
        new MockParam(ALIAS_NAME_PARAMETER, null),
        new MockParam(ALIAS_URI_PARAMETER, "aliasUrl")
    );

    assertEquals(MISSING_ALIAS_NAME, t.processRequest(req));
  }

  @Test
  public void processRequest_incorrectAliasLength_nameOnlySpaces() throws EsgException {
    final HttpServletRequest req = QuickMocker.httpServletRequest(
        new MockParam(ALIAS_NAME_PARAMETER, "  "),
        new MockParam(ALIAS_URI_PARAMETER, null)
    );

    assertEquals(INCORRECT_ALIAS_LENGTH, t.processRequest(req));
  }


  @Test
  public void processRequest_incorrectAliasLength_incorrectAliasName() throws EsgException {
    final HttpServletRequest req = QuickMocker.httpServletRequest(
        new MockParam(ALIAS_NAME_PARAMETER, "[]"),
        new MockParam(ALIAS_URI_PARAMETER, null)
    );

    assertEquals(INCORRECT_ALIAS_NAME, t.processRequest(req));
  }

  @Test
  public void processRequest_incorrectUriLengthWhenOver1000Characters() throws EsgException {
    final StringBuilder uriOver1000Characters = new StringBuilder();

    for (int i = 0; i < 1001; i++) {
      uriOver1000Characters.append("a");
    }

    final HttpServletRequest req = QuickMocker.httpServletRequest(
        new MockParam(ALIAS_NAME_PARAMETER, "name"),
        new MockParam(ALIAS_URI_PARAMETER, uriOver1000Characters.toString())
    );

    assertEquals(INCORRECT_URI_LENGTH, t.processRequest(req));
  }

}
