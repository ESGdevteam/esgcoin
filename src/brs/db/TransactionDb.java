package brs.db;

import brs.EsgException;
import brs.Transaction;
import brs.schema.tables.records.TransactionRecord;

import java.util.List;

public interface TransactionDb extends Table {
  Transaction findTransaction(long transactionId);

  Transaction findTransactionByFullHash(String fullHash); // TODO add byte[] method

  boolean hasTransaction(long transactionId);

  boolean hasTransactionByFullHash(String fullHash); // TODO add byte[] method

  Transaction loadTransaction(TransactionRecord transactionRecord) throws EsgException.ValidationException;

  List<Transaction> findBlockTransactions(long blockId);

  void saveTransactions(List<Transaction> transactions);
}
