package brs.db.store;

import brs.Account;
import brs.db.AmzKey;
import brs.db.VersionedBatchEntityTable;
import brs.db.VersionedEntityTable;

import java.util.Collection;

/**
 * Interface for Database operations related to Accounts
 */
public interface AccountStore {

  VersionedBatchEntityTable<Account> getAccountTable();

  VersionedEntityTable<Account.RewardRecipientAssignment> getRewardRecipientAssignmentTable();

  AmzKey.LongKeyFactory<Account.RewardRecipientAssignment> getRewardRecipientAssignmentKeyFactory();

  AmzKey.LinkKeyFactory<Account.AccountAsset> getAccountAssetKeyFactory();

  VersionedEntityTable<Account.AccountAsset> getAccountAssetTable();

  int getAssetAccountsCount(long assetId);

  AmzKey.LongKeyFactory<Account> getAccountKeyFactory();

  Collection<Account.RewardRecipientAssignment> getAccountsWithRewardRecipient(Long recipientId);

  Collection<Account.AccountAsset> getAssets(int from, int to, Long id);

  Collection<Account.AccountAsset> getAssetAccounts(long assetId, int from, int to);

  Collection<Account.AccountAsset> getAssetAccounts(long assetId, int height, int from, int to);
  // returns true iff:
  // this.publicKey is set to null (in which case this.publicKey also gets set to key)
  // or
  // this.publicKey is already set to an array equal to key
  boolean setOrVerify(Account acc, byte[] key, int height);
}
