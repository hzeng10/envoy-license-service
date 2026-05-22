# License Validation Service — Design

## 1. Problem Statement
Backend services running inside an Istio service mesh need license-controlled access. Every HTTP/gRPC request to a protected URL must carry a valid license credential. Validation must add < 5 ms p99 latency even when delegating to an external license backend, and must survive that backend becoming slow or unavailable.

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Kubernetes default namespace                      │
│                                                                      │
│  Client ──► istio-proxy ──► ext_authz (gRPC, port 9191) ──────────► │
│                                  │                                  │
│                    ┌─────────────┴──────────────┐                   │
│                    │  License Validation Service │                   │
│                    │  Spring Boot 3.5 / JDK 21   │                   │
│                    │                             │                   │
│                    │  UrlMatcher (Netty thread)  │                   │
│                    │  ↓ match → CompiledRule     │                   │
│                    │  ValidationCache (Caffeine) │                   │
│                    │  ↓ miss                     │                   │
│                    │  LicenseValidator (async)   │                   │
│                    │  ↓ CompletableFuture        │                   │
│                    │  CheckResponse              │                   │
│                    └─────────────────────────────┘                   │
│                                  │                                   │
│                    ┌─────────────▼──────────────┐                   │
│                    │  External License Backend   │                   │
│                    │  (optional, per rule)       │                   │
│                    └─────────────────────────────┘                   │
└─────────────────────────────────────────────────────────────────────┘
```

Istio `AuthorizationPolicy` (CUSTOM action, `paths: ["/*"]`) routes every request to this service before forwarding to the backend. The service's decision (allow/deny) is final for the sidecar.

## 3. Hot-Path Sequence Diagram

```
Netty thread
    │
    ▼
ExtAuthzService.check(CheckRequest)
    │
    ├─ ruleSetHolder.current() [volatile read, lock-free]
    │       │
    │       ▼
    │   RuleSet.match(path, method) [iterate compiled rules, O(n)]
    │       │
    │  ┌────┴──────────────────┐
    │  │  no match             │  match → CompiledRule
    │  ▼                       ▼
    │ allow/deny              ValidationCache.getOrLoad(cacheKey, ...)
    │ (unmatched policy)           │
    │                         ┌───┴────────────────────────┐
    │                         │ cache hit (TTL valid)       │ cache miss
    │                         ▼                            ▼
    │                  cached ValidationResult    LicenseValidator.validate(req)
    │                         │                   (CompletableFuture, non-blocking)
    │                         │                            │
    │                         │               [remote: Resilience4j-decorated]
    │                         │               JDK HttpClient.sendAsync(...)
    │                         │                            │
    │                         └────────────────────────────┘
    │                                          │
    │                              whenComplete (HttpClient executor thread)
    │                                          │
    │                              DenyResponseBuilder.allow() / .deny()
    │                                          │
    │                              StreamObserver.onNext/onCompleted
    │                              [thread-safe, no lock on Netty thread]
    ▼
```

## 4. Threading Model

| Thread pool | Sizing | Responsibility |
|---|---|---|
| Netty boss threads | 1 | Accept incoming TCP connections |
| Netty worker threads | `CPU * 2` | Read gRPC frames, decode, invoke `check()` |
| HttpClient executor | 16 fixed threads | Async HTTP I/O for `RemoteHttpValidator` |
| Caffeine async executor | ForkJoinPool | Expire cache entries |
| Resilience4j timer executor | 1 per remote rule | TimeLimiter scheduling |
| Rule-watcher scheduler | 1 | mtime polling |
| Spring Boot server | embedded Tomcat (8080) — requires `spring-boot-starter-web` | Actuator endpoints only (health, prometheus) |

**Key invariant:** Netty worker threads never block. The `check()` method registers a `whenComplete` callback on the `CompletableFuture` returned by the cache/validator and returns immediately.

## 5. LicenseValidator SPI

```java
public interface LicenseValidator {
    String name();
    CompletableFuture<ValidationResult> validate(ValidationRequest request);
}
```

### Contract
- **Thread-safe**: called concurrently from multiple Netty threads
- **Non-blocking**: must not park the calling thread
- In-process validators: return `CompletableFuture.completedFuture(...)` — zero cost
- Remote validators: return a future backed by `HttpClient.sendAsync(...)`

### Registration
Any Spring `@Component` implementing `LicenseValidator` is auto-registered in `ValidatorRegistry` via `List<LicenseValidator>` constructor injection. The `name()` value must match the `validator` field in `license-rules.yml`.

### Built-in validators

| Name | Class | I/O |
|---|---|---|
| `default-header-token` | `DefaultHeaderTokenValidator` | none |
| `remote-http` | `RemoteHttpValidator` | HTTP to external backend |

## 6. YAML Configuration Schema

See the detailed schema in the approved implementation plan (`/home/z00841170/.claude/plans/parsed-snacking-sphinx.md §6`), which defines:
- All fields with types, required/optional, and default values
- `default-header-token` config keys: `headerName`, `expectedToken`, `validFrom`, `validUntil`
- `remote-http` config keys: `endpoint`, `method`, `tokenHeader`, `successStatus`, `denyStatus`
- Resolution rules (first match wins; inheritance from `defaults`)
- Startup validation (fail-fast on bad config)

### Worked example
```yaml
defaults:
  unmatchedPolicy: allow
  cacheTtlSeconds: 30
  deny:
    status: 403
    body: '{"code":"LICENSE_INVALID"}'
    headers: { content-type: application/json }

rules:
  - id: orders-api
    match: { path: /api/orders/**, methods: [GET, POST] }
    validator: default-header-token
    config:
      expectedToken: ${LICENSE_TOKEN:demo-token}
      validFrom: 2026-01-01T00:00:00Z
      validUntil: 2030-12-31T23:59:59Z

  - id: premium-reports
    match: { path: /api/reports/**, methods: [GET] }
    validator: remote-http
    config:
      endpoint: http://license-backend.default.svc.cluster.local:8080/validate
    cacheTtlSeconds: 60
    remote: { timeoutMs: 80, onFailure: failOpen }
    deny: { status: 402, body: '{"code":"LICENSE_EXPIRED"}' }
```

## 7. Caching Strategy

**Cache**: Caffeine `AsyncCache<CacheKey, TimedResult>` (max 100 k entries).

**Key composition**: `ruleId + tokenValue`
- Path and method are excluded: the license decision depends only on the token and the rule, not the specific path variant within a rule
- Prevents unbounded cache growth from high-cardinality paths

**TTL semantics**:
- Positive results: `rule.cacheTtlSeconds` (default 30 s)
- Negative results: `rule.negativeCacheTtlSeconds` (default 5 s)
- TTL is stored as `expiresAtNanos` in the `TimedResult` wrapper; expiry triggers a cache invalidation and re-load

**Stampede prevention**: Caffeine `AsyncCache.get(key, loader)` coalesces concurrent requests with the same key — only one upstream call is made regardless of concurrency.

**Reload semantics**: on hot-reload, cache entries are kept by default (TTL bounds staleness). Set `flushCacheOnReload: true` to invalidate all entries immediately.

## 8. Resilience Strategy

Applied only for rules with a non-null `remoteConfig`. Per-rule `ResilienceDecorators` instance:

| Decorator | Default | Purpose |
|---|---|---|
| TimeLimiter | 100 ms | Cancel slow backend calls |
| Bulkhead | 64 concurrent | Prevent resource exhaustion under spike |
| CircuitBreaker | 50% failure / 50 calls → open 10 s | Fast-fail when backend is down |

**Failure policy** (`onFailure`):
- `failClosed` (default): deny with HTTP 503 on error
- `failOpen`: allow on error (risk: bypasses license check when backend is down)

**Circuit breaker states**: closed → open (on threshold) → half-open (after `openStateSeconds`) → closed/open (based on trial calls).

## 9. Istio Integration

### Approach: EnvoyFilter (no mesh config modification)

The service is wired into the mesh via an `EnvoyFilter` resource rather than the extension provider + `AuthorizationPolicy (CUSTOM)` approach. This avoids any modification to the `istio-system/istio` ConfigMap, requires no namespace labels, and is compatible with Istio 1.30's default webhook configuration.

### EnvoyFilter (`k8s/06-envoyfilter.yaml`)

Injects `envoy.filters.http.ext_authz` into every `SIDECAR_INBOUND` HTTP filter chain in the `default` namespace, immediately before `envoy.filters.http.router`:

```yaml
applyTo: HTTP_FILTER
match:
  context: SIDECAR_INBOUND
  listener:
    filterChain:
      filter:
        name: envoy.filters.network.http_connection_manager
        subFilter:
          name: envoy.filters.http.router
patch:
  operation: INSERT_BEFORE
  value:
    name: envoy.filters.http.ext_authz
    typed_config:
      "@type": type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthz
      grpc_service:
        envoy_grpc:
          cluster_name: outbound|9191||license-validation-service.default.svc.cluster.local
        timeout: 0.25s
      transport_api_version: V3
      failure_mode_allow: false
```

### DestinationRule (`k8s/08-destinationrule.yaml`)

Disables Istio mTLS **and** the `istio.metadata_exchange` cluster filter for the license service:

```yaml
trafficPolicy:
  tls:
    mode: DISABLE
```

**Why this is required:** Even without mTLS configured, Istio's `istio.metadata_exchange` filter (with `enable_discovery: true`) injects a binary magic prefix before the HTTP/2 preface when connecting to non-sidecar endpoints. The license service's Netty gRPC server does not implement this protocol extension and stalls, causing every ext_authz call to time out. Setting `mode: DISABLE` removes all `transportSocketMatches` from the upstream cluster and suppresses the handshake, restoring plain HTTP/2 gRPC connectivity.

### Per-pod sidecar injection (unlabeled namespace)

The `default` namespace has no `istio-injection=enabled` label. Workloads opt in per-pod by setting `sidecar.istio.io/inject: "true"` as a pod **label** (not annotation). Istio 1.30's mutating webhook has a rule that matches pods with this label in namespaces that have neither `istio-injection` nor `istio.io/rev` labels.

Setting it as an **annotation** has no effect; the webhook `objectSelector` only matches labels.

### License service — no sidecar

The license service pod has `sidecar.istio.io/inject: "false"`. It is not a mesh workload. The ext_authz gRPC port (9191) receives connections directly from Envoy sidecars in the mesh without any Istio proxy in the path.

### Traffic interception scope

Only requests that arrive at a pod with an Istio sidecar are intercepted by the EnvoyFilter. Pods without sidecars (e.g., batch jobs, pods without the inject label) bypass ext_authz. This is expected and documented behaviour.

### Envoy response flags

| Scenario | Envoy flag | HTTP status |
|---|---|---|
| ext_authz returns DENY | `UAEX` | 403 (configurable) |
| ext_authz times out / connection fails | `UAEX` (`failure_mode_allow: false`) | 403 |
| ext_authz returns OK | — | upstream response |
| Non-sidecar source pod | — | upstream response (no check) |

### Loop prevention

The `license-validation-service` deployment has annotation:
```yaml
traffic.sidecar.istio.io/excludeOutboundPorts: "9191"
```
and `sidecar.istio.io/inject: "false"`, so its own outbound gRPC traffic never triggers an ext_authz check on itself.

## 10. Observability

### Metrics (Micrometer / Prometheus)
| Metric | Labels | Description |
|---|---|---|
| `license.check.result_total` | `decision=allow\|deny\|unmatched` | Check outcomes |
| `license.check.duration` | — | Hot-path latency histogram |
| `license.rule.reload.success_total` | — | Successful rule reloads |
| `license.rule.reload.failed_total` | — | Failed rule reloads (parse errors) |

Prometheus scrape endpoint: `http://pod:8080/actuator/prometheus`

### Logging
- Structured log at INFO level for rule loads and reloads
- WARN for unexpected validator errors and backend status codes
- DEBUG for per-request details (disabled by default in production)
- Token values are never logged

### Health endpoints
- Liveness: `http://pod:8080/actuator/health/liveness`
- Readiness: `http://pod:8080/actuator/health/readiness`

## 11. Performance Budget

| Scenario | p50 target | p99 target |
|---|---|---|
| Default validator (in-process), warm cache | < 0.5 ms | < 2 ms |
| Default validator, cold cache | < 1 ms | < 3 ms |
| Remote validator, warm cache | < 0.5 ms | < 2 ms |
| Remote validator, cold (backend 5 ms RTT) | < 6 ms | < 15 ms |
| Circuit breaker open | < 0.1 ms | < 0.5 ms |

### Tuning levers
- Increase `bulkheadMaxConcurrent` if CPU-bound under high concurrency
- Lower `timeoutMs` aggressively — 50 ms is reasonable for intra-cluster calls
- Use `cacheTtlSeconds: 300` for stable, long-lived license tokens
- ZGC flags (`-XX:+UseZGC -XX:+ZGenerational`) minimize GC pause contribution to p99

## 12. Security Considerations

- **Token logging**: token values are never included in log output
- **Config injection**: YAML is parsed in a sandboxed classpath context; SnakeYAML SafeConstructor is in use via Jackson's YAMLFactory
- **Transport**: Istio handles mTLS between Envoy and this service; the gRPC port is not exposed outside the cluster
- **Env var interpolation**: `${VAR:default}` is resolved through Spring's `Environment`, which uses OS environment variables and JVM system properties; no arbitrary script execution
