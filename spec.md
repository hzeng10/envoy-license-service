# License Validation Service — Specification

## Requirements

### 1. Tech Stack
Spring Boot 3.5.3 + JDK 21, Maven 3.9.x at `/opt/maven`.

### 2. Deployment Context
The service runs inside a Kubernetes + Istio cluster in the `default` namespace. It acts as an **Envoy External Authorization** (ext_authz) service using the gRPC-based `envoy.service.auth.v3.Authorization` protocol. When a request arrives at any backend service inside the mesh:
- Envoy sidecar sends a `CheckRequest` to this service
- This service replies with `CheckResponse` (allow or deny)
- Allow → request is forwarded to the backend
- Deny → request is rejected with the configured response (status, body, headers)

### 3. License Rule Configuration
Rules are defined in a YAML file (`license-rules.yml`) and injected via a Kubernetes ConfigMap mounted at `/etc/license/license-rules.yml`. The service watches the file for changes and **hot-reloads** rules without a pod restart.

- **Different URLs** can have different validation logic
- A **default validation logic** is provided out of the box (`default-header-token` validator)
- The default can be replaced by a **custom validator** registered as a Spring bean implementing the `LicenseValidator` interface
- A unified async validation interface is defined for extensibility

Formal YAML schema: see `design.md §6`.

### 4. Performance
The service must handle **high concurrency at low latency (ms-level p99)**. Design targets:
- p50 < 1 ms, p99 < 5 ms at ≥10 k RPS per pod (default validator, warm cache)
- Some validators call external license backends; the hot path is **non-blocking** (async `CompletableFuture`)
- Validation results are cached (Caffeine `AsyncCache`) with configurable TTL per rule
- Resilience4j circuit breaker, bulkhead, and time limiter protect against slow/unavailable backends

### 5. No-Bypass Guarantee
Enforcement is at the Istio layer via an `EnvoyFilter` resource:

- `k8s/06-envoyfilter.yaml` injects `envoy.filters.http.ext_authz` directly into every `SIDECAR_INBOUND` HTTP filter chain in the `default` namespace, before `envoy.filters.http.router`
- `failure_mode_allow: false` — if the ext_authz service is unreachable, Envoy denies the request
- This approach does **not** modify the `istio-system/istio` ConfigMap, does not require a namespace `istio-injection` label, and does not require an `AuthorizationPolicy`
- Requests from pods **without** an Istio sidecar bypass ext_authz (expected; batch jobs / init containers are not mesh workloads)
- Operators MUST NOT remove the EnvoyFilter for any namespace containing URLs listed in `license-rules.yml`
- The service's `unmatchedPolicy` (default: `allow`) governs URLs with no configured rule

### 6. Kubernetes and Istio Manifests
Provided in `k8s/`:
- `01-configmap.yaml` — license rules ConfigMap
- `02-deployment.yaml` — 1 replica, resource limits (256 Mi req / 384 Mi limit), liveness/readiness probes, ConfigMap volume mount; **no Istio sidecar** (`sidecar.istio.io/inject: "false"`)
- `03-service.yaml` — ClusterIP, ports 9191 (grpc) + 8080 (http-management)
- `06-envoyfilter.yaml` — injects ext_authz HTTP filter into all SIDECAR_INBOUND chains (no mesh config modification)
- `07-httpbin.yaml` — httpbin test workload with per-pod sidecar injection
- `08-destinationrule.yaml` — disables Istio mTLS + `istio.metadata_exchange` for the license service to allow plain gRPC

> Files `04-istio-extensionprovider.yaml` and `05-istio-authorizationpolicy.yaml` are provided for reference but are **not applied** in the current deployment.

Local deployment guide: `deploy.md`

### 7. Quality
- Maven 3.9.x at `/opt/maven`; `mvn verify` builds, tests, and enforces coverage
- Only commercially-friendly third-party libraries (Apache-2.0, MIT, EPL-2.0) — no GPL/LGPL/AGPL
- Unit test coverage ≥ 85% lines and branches, enforced by JaCoCo (build fails otherwise)
- See `README.md` for complete third-party library inventory
- The `spring-boot-starter-web` dependency is required — without it, Spring Boot does not start an embedded Tomcat server and the actuator port 8080 is never bound, causing liveness probe failures.

## Acceptance Criteria

| # | Criterion |
|---|---|
| R1 | Service compiles and all tests pass with `/opt/maven/bin/mvn verify` |
| R2 | JaCoCo report shows ≥85% line and branch coverage |
| R3 | gRPC server starts on port 9191; requests checked against loaded rules |
| R4 | Valid token → allow; invalid token → deny with configured 4xx response |
| R5 | Rules reload from mounted ConfigMap within `watchIntervalSeconds` |
| R6 | Unmatched URLs → controlled by `unmatchedPolicy` |
| R7 | Custom validator registered as `@Component` is auto-discovered |
| R8 | Remote validator uses async JDK HttpClient + Caffeine cache + Resilience4j |
| R9 | Kubernetes manifests deploy successfully on minikube + Istio 1.30 via EnvoyFilter approach (no mesh ConfigMap modification) |
| R10 | `design.md` and `README.md` are complete with library license table |
