package brs;

import brs.peer.Peer;
import brs.util.Observable;
import com.google.gson.JsonObject;

import java.util.List;

public interface TransactionProcessor extends Observable<List<? extends Transaction>,TransactionProcessor.Event> {

  enum Event {
    REMOVED_UNCONFIRMED_TRANSACTIONS,
    ADDED_UNCONFIRMED_TRANSACTIONS,
    ADDED_CONFIRMED_TRANSACTIONS,
    ADDED_DOUBLESPENDING_TRANSACTIONS
  }

  List<Transaction> getAllUnconfirmedTransactions();

  int getAmountUnconfirmedTransactions();

  List<Transaction> getAllUnconfirmedTransactionsFor(Peer peer);

  void markFingerPrintsOf(Peer peer, List<Transaction> transactions);
  
  Transaction getUnconfirmedTransaction(long transactionId);

  void clearUnconfirmedTransactions();

  Integer broadcast(Transaction transaction) throws EsgException.ValidationException;

  void processPeerTransactions(JsonObject request, Peer peer) throws EsgException.ValidationException;

  Transaction parseTransaction(byte[] bytes) throws EsgException.ValidationException;

  Transaction parseTransaction(JsonObject json) throws EsgException.ValidationException;

  Transaction.Builder newTransactionBuilder(byte[] senderPublicKey, long amountNQT, long feeNQT, short deadline, Attachment attachment);

  int getTransactionVersion(int blockHeight);

}
