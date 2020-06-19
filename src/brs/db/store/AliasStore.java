package brs.db.store;

import brs.Alias;
import brs.db.AmzKey;
import brs.db.VersionedEntityTable;

import java.util.Collection;

public interface AliasStore {
  AmzKey.LongKeyFactory<Alias> getAliasDbKeyFactory();
  AmzKey.LongKeyFactory<Alias.Offer> getOfferDbKeyFactory();

  VersionedEntityTable<Alias> getAliasTable();

  VersionedEntityTable<Alias.Offer> getOfferTable();

  Collection<Alias> getAliasesByOwner(long accountId, int from, int to);

  Alias getAlias(String aliasName);
}
