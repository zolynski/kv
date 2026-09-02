import { DecimalPipe } from '@angular/common';
import { Component, DestroyRef, computed, inject, resource, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ApiResult,
  KeyLocation,
  KvStoreService,
  ShardView,
  Topology
} from './kv-store.service';

interface Outcome {
  ok: boolean;
  text: string;
  shard: number | null;
  status: number;
}

@Component({
  selector: 'app-root',
  imports: [FormsModule, DecimalPipe],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly api = inject(KvStoreService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly keyInput = signal('');
  protected readonly valueInput = signal('');
  protected readonly limit = signal(60);

  protected readonly outcome = signal<Outcome | null>(null);
  protected readonly busy = signal(false);

  private readonly topologyResource = resource<Topology | undefined, unknown>({
    loader: ({ abortSignal }) => this.api.readJson<Topology>(this.api.topologyUrl, abortSignal),
  });

  private readonly keysResource = resource({
    params: () => this.limit(),
    loader: ({ params, abortSignal }) =>
      this.api.readJson<KeyLocation[]>(this.api.keysUrl(params), abortSignal),
    defaultValue: [] as KeyLocation[],
  });

  protected readonly topology = this.topologyResource.value;
  protected readonly keys = this.keysResource.value;
  protected readonly offline = computed(() => this.topologyResource.error() !== undefined);

  constructor() {
    const timer = setInterval(() => this.refresh(), 2000);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  protected refresh(): void {
    this.topologyResource.reload();
    this.keysResource.reload();
  }

  protected fillRatio(shard: ShardView): number {
    const store = shard.store;
    return !store || store.capacity <= 0 ? 0 : Math.min(1, store.entries / store.capacity);
  }

  protected sharePercent(shard: ShardView): number {
    const total = this.topology()?.totalEntries ?? 0;
    const entries = shard.store?.entries ?? 0;
    return total === 0 ? 0 : (entries / total) * 100;
  }

  protected async put(): Promise<void> {
    const key = this.keyInput();
    if (!key) {
      return;
    }
    await this.run(
      () => this.api.put(key, this.valueInput()),
      (result) =>
        result.status === 201
          ? `Created "${key}"`
          : `Updated "${key}" (previous value: ${quote(result.body)})`,
    );
  }

  protected async get(): Promise<void> {
    const key = this.keyInput();
    if (!key) {
      return;
    }
    await this.run(
      () => this.api.get(key),
      (result) => {
        this.valueInput.set(result.body);
        return `Found "${key}"`;
      },
    );
  }

  protected async remove(key = this.keyInput()): Promise<void> {
    if (!key) {
      return;
    }
    await this.run(
      () => this.api.remove(key),
      () => `Deleted "${key}"`,
    );
  }

  protected async select(entry: KeyLocation): Promise<void> {
    this.keyInput.set(entry.key);
    await this.get();
  }

  protected async clearAll(): Promise<void> {
    if (!confirm('Delete every key in the cluster?')) {
      return;
    }
    await this.run(
      () => this.api.clear(),
      () => 'Cluster emptied',
    );
  }

  protected async seed(): Promise<void> {
    // Writes enough keys to make the distribution obvious. One request per key on purpose: it is
    // the same path a real client takes, and it exercises the routing under a little load.
    this.busy.set(true);
    const total = 300;
    const writes = Array.from({ length: total }, (_, i) =>
      this.api.put(`demo:${Math.random().toString(36).slice(2, 8)}:${i}`, `value ${i}`),
    );
    await Promise.all(writes);
    this.busy.set(false);
    this.outcome.set({ ok: true, text: `Wrote ${total} keys`, shard: null, status: 200 });
    this.refresh();
  }

  /// Runs one command and turns its result into the line under the form.
  private async run(
    call: () => Promise<ApiResult>,
    describe: (result: ApiResult) => string,
  ): Promise<void> {
    this.busy.set(true);
    try {
      const result = await call();
      const ok = result.status >= 200 && result.status < 400;
      this.outcome.set({
        ok,
        text: ok ? describe(result) : errorText(result),
        shard: result.shard,
        status: result.status,
      });
    } finally {
      this.busy.set(false);
      this.refresh();
    }
  }
}

function quote(value: string): string {
  return value === '' ? '(empty)' : `"${value}"`;
}

function errorText(result: ApiResult): string {
  switch (result.status) {
    case 0:
      return 'Router unreachable';
    case 400:
      return problemDetail(result.body) ?? 'Invalid request';
    case 404:
      return 'No such key';
    case 421:
      return 'Misdirected key (421) - the router and the cluster disagree on shard count';
    case 507:
      return 'Store is full - the shard rejected the write (507)';
    default:
      return `HTTP ${result.status}`;
  }
}

function problemDetail(body: string): string | null {
  try {
    const problem = JSON.parse(body) as { rule?: string; detail?: string };
    if (!problem.detail) {
      return problem.rule ? problem.rule.toLowerCase().replaceAll('_', ' ') : null;
    }
    return problem.rule ? `${problem.detail} (${problem.rule})` : problem.detail;
  } catch {
    return null;
  }
}
