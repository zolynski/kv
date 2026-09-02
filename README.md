# Key–Value Store

Untyped key–value store, partitioned across N in-memory shards, accessed via stateless
routing tier and an example admin UI in Angular.

## How to run it

### Kubernetes

```bash
docker build --build-arg MODULE=kv-shard  -t kv-shard:1.0.0  .
docker build --build-arg MODULE=kv-router -t kv-router:1.0.0 .
docker build -t kv-ui:1.0.0 ./ui

./scripts/load-images.sh
kubectl apply -f k8s/
./scripts/demo.sh
```

Then open <http://localhost:4200>.

### docker-compose

```bash
docker network create web
docker compose up -d --wait
```

API on <http://localhost:8080>, UI on <http://localhost:4200>.

## API

| Action   | Path                   | Response                                                   |
|----------|------------------------|------------------------------------------------------------|
| `PUT`    | `/api/v1/keys/{key}`   | `201` created · `200` replaced, body is the previous value |
| `GET`    | `/api/v1/keys/{key}`   | `200` with the value · `404`                               |
| `DELETE` | `/api/v1/keys/{key}`   | `204` · `404`                                              |
| `GET`    | `/api/v1/keys?limit=N` | bounded fan-out; `[{key, shard}]`                          |
| `DELETE` | `/api/v1/keys`         | empties every shard                                        |
| `GET`    | `/api/v1/topology`     | cluster layout and per-shard occupancy                     |

Every response carries `X-KV-Shard`, naming the shard that served it.

| Violation                |                                                                                                                                                                                          |
|--------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `KEY_TOO_LONG`           | **1024 UTF-8 bytes**                                                                                                                                                                     |
| `KEY_CONTROL_CHARACTER`  | No C0 controls or DEL                                                                                                   |
| `KEY_RESERVED_CHARACTER` | No backslash                                                                                                                                                                   |
| `VALUE_TOO_LONG`         | **100 KiB** per value |


## Configuration

| Variable | Default | |
|---|---|---|
| `KV_ROUTER_SHARD_COUNT` | `1` | **must equal the StatefulSet's replicas** |
| `KV_ROUTER_SHARD_URL_TEMPLATE` | — | `{ordinal}` is substituted per shard |
| `KV_SHARD_COUNT` / `KV_SHARD_ORDINAL` | `1` / derived from pod name | `-1` derives it |
| `KV_STORE_IMPLEMENTATION` | `chained` | or `concurrent-hash-map` |
| `KV_STORE_MAX_ENTRIES` | `1000000` | writes past this get `507`; with the 100 KiB value rule this bounds the store |


## Build and test

```bash
mvn test                      # 92 tests
cd ui && npx ng test          # 9 tests
./scripts/benchmark.sh        # JMH, ~4 min, writes benchmarks/
```

## Packages

```
kv-core/         common APIs, hashing and partitioning
kv-shard/        data storing shard
kv-router/       stateless router for shard access
kv-benchmarks/   benchmarking setup
ui/              admin UI example
k8s/             namespace, StatefulSet, Deployment, Services
```
