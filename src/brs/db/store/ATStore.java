package brs.db.store;

import brs.at.AT;
import brs.db.EsgKey;
import brs.db.VersionedEntityTable;

import java.util.Collection;
import java.util.List;

public interface ATStore {

  boolean isATAccountId(Long id);

  List<Long> getOrderedATs();

  AT getAT(Long id);

  List<Long> getATsIssuedBy(Long accountId);

  Collection<Long> getAllATIds();

  EsgKey.LongKeyFactory<AT> getAtDbKeyFactory();

  VersionedEntityTable<AT> getAtTable();

  EsgKey.LongKeyFactory<AT.ATState> getAtStateDbKeyFactory();

  VersionedEntityTable<AT.ATState> getAtStateTable();

  Long findTransaction(int startHeight, int endHeight, Long atID, int numOfTx, long minAmount);

  int findTransactionHeight(Long transactionId, int height, Long atID, long minAmount);
}
