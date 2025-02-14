package brs.db.store;

import brs.Subscription;
import brs.db.EsgKey;
import brs.db.VersionedEntityTable;

import java.util.Collection;

public interface SubscriptionStore {

  EsgKey.LongKeyFactory<Subscription> getSubscriptionDbKeyFactory();

  VersionedEntityTable<Subscription> getSubscriptionTable();

  Collection<Subscription> getSubscriptionsByParticipant(Long accountId);

  Collection<Subscription> getIdSubscriptions(Long accountId);

  Collection<Subscription> getSubscriptionsToId(Long accountId);

  Collection<Subscription> getUpdateSubscriptions(int timestamp);
}
