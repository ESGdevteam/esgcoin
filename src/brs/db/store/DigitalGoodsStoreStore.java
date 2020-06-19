package brs.db.store;

import brs.DigitalGoodsStore;
import brs.crypto.EncryptedData;
import brs.db.AmzKey;
import brs.db.VersionedEntityTable;
import brs.db.VersionedValuesTable;

import java.util.Collection;

public interface DigitalGoodsStoreStore {

  AmzKey.LongKeyFactory<DigitalGoodsStore.Purchase> getFeedbackDbKeyFactory();

  AmzKey.LongKeyFactory<DigitalGoodsStore.Purchase> getPurchaseDbKeyFactory();

  VersionedEntityTable<DigitalGoodsStore.Purchase> getPurchaseTable();

  VersionedValuesTable<DigitalGoodsStore.Purchase, EncryptedData> getFeedbackTable();

  AmzKey.LongKeyFactory<DigitalGoodsStore.Purchase> getPublicFeedbackDbKeyFactory();

  VersionedValuesTable<DigitalGoodsStore.Purchase, String> getPublicFeedbackTable();

  AmzKey.LongKeyFactory<DigitalGoodsStore.Goods> getGoodsDbKeyFactory();

  VersionedEntityTable<DigitalGoodsStore.Goods> getGoodsTable();

  Collection<DigitalGoodsStore.Goods> getGoodsInStock(int from, int to);

  Collection<DigitalGoodsStore.Goods> getSellerGoods(long sellerId, boolean inStockOnly, int from, int to);

  Collection<DigitalGoodsStore.Purchase> getAllPurchases(int from, int to);

  Collection<DigitalGoodsStore.Purchase> getSellerPurchases(long sellerId, int from, int to);

  Collection<DigitalGoodsStore.Purchase> getBuyerPurchases(long buyerId, int from, int to);

  Collection<DigitalGoodsStore.Purchase> getSellerBuyerPurchases(long sellerId, long buyerId, int from, int to);

  Collection<DigitalGoodsStore.Purchase> getPendingSellerPurchases(long sellerId, int from, int to);

  Collection<DigitalGoodsStore.Purchase> getExpiredPendingPurchases(int timestamp);
}
