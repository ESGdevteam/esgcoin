package brs.db.store;

import brs.Alias;
import brs.db.EsgKey;
import brs.db.VersionedEntityTable;

import java.util.Collection;

public interface AliasStore {
  EsgKey.LongKeyFactory<Alias> getAliasDbKeyFactory();
  EsgKey.LongKeyFactory<Alias.Offer> getOfferDbKeyFactory();

  VersionedEntityTable<Alias> getAliasTable();

  VersionedEntityTable<Alias.Offer> getOfferTable();

  Collection<Alias> getAliasesByOwner(long accountId, int from, int to);

  Alias getAlias(String aliasName);
}
