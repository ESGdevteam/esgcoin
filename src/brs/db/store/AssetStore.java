package brs.db.store;

import brs.Asset;
import brs.db.AmzKey;
import brs.db.sql.EntitySqlTable;

import java.util.Collection;

public interface AssetStore {
  AmzKey.LongKeyFactory<Asset> getAssetDbKeyFactory();

  EntitySqlTable<Asset> getAssetTable();

  Collection<Asset> getAssetsIssuedBy(long accountId, int from, int to);
}
