package brs;

import brs.db.AmzKey;

public class Asset {

  private final long assetId;
  public final AmzKey dbKey;
  private final long accountId;
  private final String name;
  private final String description;
  private final long quantityQNT;
  private final byte decimals;

  protected Asset(long assetId, AmzKey dbKey, long accountId, String name, String description, long quantityQNT, byte decimals) {
    this.assetId = assetId;
    this.dbKey = dbKey;
    this.accountId = accountId;
    this.name = name;
    this.description = description;
    this.quantityQNT = quantityQNT;
    this.decimals = decimals;
  }

  public Asset(AmzKey dbKey, Transaction transaction, Attachment.ColoredCoinsAssetIssuance attachment) {
    this.dbKey = dbKey;
    this.assetId = transaction.getId();
    this.accountId = transaction.getSenderId();
    this.name = attachment.getName();
    this.description = attachment.getDescription();
    this.quantityQNT = attachment.getQuantityQNT();
    this.decimals = attachment.getDecimals();
  }

  public long getId() {
    return assetId;
  }

  public long getAccountId() {
    return accountId;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public long getQuantityQNT() {
    return quantityQNT;
  }

  public byte getDecimals() {
    return decimals;
  }

}
