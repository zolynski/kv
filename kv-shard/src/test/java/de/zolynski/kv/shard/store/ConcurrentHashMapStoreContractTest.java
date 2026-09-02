package de.zolynski.kv.shard.store;

import de.zolynski.kv.core.store.Store;
import de.zolynski.kv.core.store.StoreConfig;

///  The contract, verified against the JDK baseline.
class ConcurrentHashMapStoreContractTest extends StoreContractTest {

  @Override
  protected Store newStore(StoreConfig config) {
    return new ConcurrentHashMapStore(config);
  }
}
