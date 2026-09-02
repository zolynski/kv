import { Injectable } from '@angular/core';

export interface StoreStats {
  implementation: string;
  entries: number;
  maxEntries: number;
  segments: number;
  capacity: number;
}

export interface ShardView {
  ordinal: number;
  url: string;
  reachable: boolean;
  store: StoreStats | null;
}

/// The server's published key and value rules, so the UI never hardcodes a copy of them.
export interface Limits {
  maxKeyBytes: number;
  maxValueBytes: number;
}

export interface Topology {
  shardCount: number;
  totalEntries: number;
  limits: Limits;
  shards: ShardView[];
}

export interface KeyLocation {
  key: string;
  shard: number;
}

export interface ApiResult {
  status: number;
  body: string;
  shard: number | null;
}

@Injectable({ providedIn: 'root' })
export class KvStoreService {
  private readonly base = '/api/v1';

  readonly topologyUrl = `${this.base}/topology`;

  keysUrl(limit: number): string {
    return `${this.base}/keys?limit=${limit}`;
  }

  /// Read side, for `resource()` loaders. The abort signal comes from the resource, so a reload
  /// that supersedes an in-flight request cancels it rather than racing it.
  async readJson<T>(url: string, abortSignal: AbortSignal): Promise<T> {
    const response = await fetch(url, { signal: abortSignal });
    if (!response.ok) {
      throw new Error(`${url} responded ${response.status}`);
    }
    return (await response.json()) as T;
  }

  put(key: string, value: string): Promise<ApiResult> {
    return this.send('PUT', this.keyUrl(key), value);
  }

  get(key: string): Promise<ApiResult> {
    return this.send('GET', this.keyUrl(key));
  }

  remove(key: string): Promise<ApiResult> {
    return this.send('DELETE', this.keyUrl(key));
  }

  clear(): Promise<ApiResult> {
    return this.send('DELETE', `${this.base}/keys`);
  }

  private async send(method: string, url: string, body?: string): Promise<ApiResult> {
    try {
      const response = await fetch(url, {
        method,
        headers: body === undefined ? undefined : { 'Content-Type': 'text/plain;charset=UTF-8' },
        body,
      });
      const shard = response.headers.get('X-KV-Shard');
      return {
        status: response.status,
        body: await response.text(),
        shard: shard === null ? null : Number(shard),
      };
    } catch {
      return { status: 0, body: '', shard: null };
    }
  }

  private keyUrl(key: string): string {
    return `${this.base}/keys/${encodeKeyPath(key)}`;
  }
}

export function encodeKeyPath(key: string): string {
  return key
    .split('/')
    .map((segment) => {
      return encodeURIComponent(segment);
    })
    .join('/');
}
