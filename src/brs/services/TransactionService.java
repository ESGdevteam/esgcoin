package brs.services;

import brs.AmzException;
import brs.Transaction;

public interface TransactionService {

  boolean verifyPublicKey(Transaction transaction);

  void validate(Transaction transaction) throws AmzException.ValidationException;

  boolean applyUnconfirmed(Transaction transaction);

  void apply(Transaction transaction);

  void undoUnconfirmed(Transaction transaction);
}
