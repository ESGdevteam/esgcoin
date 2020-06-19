package brs.db.store;

import brs.Transaction;
import brs.db.AmzKey;
import brs.db.sql.EntitySqlTable;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

public interface TransactionProcessorStore {
  // WATCH: BUSINESS-LOGIC
  void processLater(Collection<Transaction> transactions);

  AmzKey.LongKeyFactory<Transaction> getUnconfirmedTransactionDbKeyFactory();

  Set<Transaction> getLostTransactions();

  Map<Long, Integer> getLostTransactionHeights();

  EntitySqlTable<Transaction> getUnconfirmedTransactionTable();

  int deleteTransaction (Transaction transaction);

  boolean hasTransaction(long transactionId);
}
