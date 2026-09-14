package de.zolynski.kv.shard.store;

import de.zolynski.kv.core.store.Store;
import de.zolynski.kv.core.store.StoreConfig;

/// The shared [Store] contract, run against [StripedChainedStore].
///
/// No new assertions: this is the same suite the other two implementations pass. If a store is
/// substitutable for them it passes unchanged, and whatever it fails is precisely the gap.
///
class StripedChainedStoreContractTest extends StoreContractTest {

  @Override
  protected Store newStore(StoreConfig config) {
    return new StripedChainedStore(config);
  }
}
