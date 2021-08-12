package brs.db.store;

import brs.Asset;
import brs.db.EsgKey;
import brs.db.sql.EntitySqlTable;

import java.util.Collection;

public interface AssetStore {
  EsgKey.LongKeyFactory<Asset> getAssetDbKeyFactory();

  EntitySqlTable<Asset> getAssetTable();

  Collection<Asset> getAssetsIssuedBy(long accountId, int from, int to);
}
