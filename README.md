# Envoy License Validation Service

An Envoy External Authorization (ext_authz) service that validates incoming HTTP/gRPC requests against configurable license rules. Runs as a gRPC server inside a Kubernetes + Istio mesh and integrates as an Istio `CUSTOM` authorization provider.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | Oracle JDK 21 at `/usr/lib/jvm/jdk-21.0.11-oracle-x64` |
| Maven | 3.9.x | Installed at `/opt/maven` |
| Docker | Any | Required for image build |
| minikube | 1.35+ | Local Kubernetes cluster |
| istioctl | 1.30.x | Istio control plane CLI |
| kubectl | 1.35+ | Kubernetes CLI |
| grpcurl | Any | Optional; for manual gRPC smoke testing |

## Build

```bash
/opt/maven/bin/mvn clean verify
```

This compiles the service, runs all unit and integration tests, and enforces the JaCoCo coverage gate (≥85% lines and branches).

## Run Locally (no Kubernetes)

```bash
# Start the service
/opt/maven/bin/mvn spring-boot:run

# gRPC server listens on :9191
# Actuator (health, metrics) listens on :8080

# Smoke test with grpcurl (install: https://github.com/fullstorydev/grpcurl)
grpcurl -plaintext -d @ localhost:9191 envoy.service.auth.v3.Authorization/Check <<'EOF'
{
  "attributes": {
    "request": {
      "http": {
        "path": "/api/orders/123",
        "method": "GET",
        "headers": { "x-license-token": "demo-token" }
      }
    }
  }
}
EOF
```

Expected response for a valid token:
```json
{ "status": {} }
```

Expected response for an invalid token:
```json
{
  "status": { "code": 7 },
  "deniedResponse": {
    "status": { "code": "Forbidden" },
    "body": "{\"code\":\"LICENSE_INVALID\",...}"
  }
}
```

## Deploy to minikube + Istio

See `deploy.md` for the complete step-by-step guide including troubleshooting. Quick summary:

```bash
# 1. Build JAR and run tests
/opt/maven/bin/mvn clean verify

# 2. Build Docker image inside minikube (note: mvn spring-boot:build-image does not work here —
#    minikube exposes Docker API v1.24 but Paketo buildpacks require ≥ v1.44)
eval $(minikube docker-env)
docker build -t license-validation-service:0.1.0 .

# 3. Apply core resources
kubectl apply -f k8s/01-configmap.yaml -f k8s/02-deployment.yaml -f k8s/03-service.yaml
kubectl rollout status deployment/license-validation-service --timeout=120s

# 4. Wire Istio — EnvoyFilter + DestinationRule (no mesh ConfigMap modification)
kubectl apply -f k8s/06-envoyfilter.yaml -f k8s/08-destinationrule.yaml

# 5. Deploy httpbin test workload
kubectl apply -f k8s/07-httpbin.yaml && kubectl rollout status deployment/httpbin --timeout=90s
```

## Configure License Rules

Rules are defined in `license-rules.yml` (or the mounted ConfigMap). Edit `k8s/01-configmap.yaml` and reapply:

```bash
kubectl apply -f k8s/01-configmap.yaml
# The service detects the file change within watchIntervalSeconds (default: 5s) and hot-reloads
```

See `design.md §6` for the complete YAML schema.

### Environment variable substitution

Use `${VAR:default}` in the rules YAML:

```yaml
config:
  expectedToken: ${LICENSE_TOKEN:demo-token}
```

Pass the real token via a Kubernetes Secret:

```bash
kubectl create secret generic license-secret --from-literal=token=my-production-token
```

The deployment (`k8s/02-deployment.yaml`) already references this secret via `secretKeyRef`.

## Extend with a Custom Validator

Implement `LicenseValidator` as a Spring `@Component`:

```java
@Component
public class MyCustomValidator implements LicenseValidator {

    @Override
    public String name() { return "my-custom"; }

    @Override
    public CompletableFuture<ValidationResult> validate(ValidationRequest request) {
        // your logic here; must be non-blocking
        boolean valid = /* ... */;
        return CompletableFuture.completedFuture(
            valid ? ValidationResult.allow()
                  : ValidationResult.deny(403, "{\"code\":\"DENIED\"}", Map.of()));
    }
}
```

Then reference it in `license-rules.yml`:

```yaml
rules:
  - id: my-rule
    match: { path: /api/custom/**, methods: [GET] }
    validator: my-custom
    config: { ... }
```

## Third-Party Libraries

| Library | Version | License | Purpose |
|---|---|---|---|
| Spring Boot | 3.5.3 | Apache-2.0 | Application framework, config binding, actuator |
| Spring Framework | 6.2.x (managed) | Apache-2.0 | DI, lifecycle management |
| Spring Web (Tomcat) | 3.5.3 | Apache-2.0 | Embedded Tomcat for actuator HTTP endpoints on port 8080 |
| grpc-netty-shaded | 1.73.0 | Apache-2.0 | gRPC Netty transport (shaded, no version conflicts) |
| grpc-protobuf | 1.73.0 | Apache-2.0 | Protocol Buffers codec for gRPC |
| grpc-stub | 1.73.0 | Apache-2.0 | gRPC generated stub base classes |
| grpc-inprocess | 1.73.0 | Apache-2.0 | In-process gRPC channel for tests |
| grpc-testing | 1.73.0 | Apache-2.0 | gRPC test utilities |
| io.envoyproxy.controlplane:api | 1.0.49 | Apache-2.0 | Envoy ext_authz v3 proto generated classes |
| protobuf-java | 3.x (managed) | BSD-3-Clause | Protocol Buffers runtime |
| Caffeine | 3.2.0 | Apache-2.0 | High-performance async result cache |
| resilience4j-circuitbreaker | 2.3.0 | Apache-2.0 | Circuit breaker for remote validators |
| resilience4j-bulkhead | 2.3.0 | Apache-2.0 | Concurrency limiter for remote validators |
| resilience4j-timelimiter | 2.3.0 | Apache-2.0 | Timeout for remote validator calls |
| Micrometer | 1.13.x (managed) | Apache-2.0 | Application metrics abstraction |
| micrometer-registry-prometheus | 1.13.x (managed) | Apache-2.0 | Prometheus metrics export |
| jackson-dataformat-yaml | 2.18.x (managed) | Apache-2.0 | YAML parsing for rules file |
| jackson-databind | 2.18.x (managed) | Apache-2.0 | JSON/YAML data binding |
| SnakeYAML | 2.x (managed) | Apache-2.0 | Low-level YAML parser (used by Jackson YAML) |
| jakarta.annotation-api | 3.x (managed) | EPL-2.0 / GPL-2.0+CE | `@Generated` annotation required by gRPC stubs |
| JUnit Jupiter | 5.11.x (managed) | EPL-2.0 | Unit and integration testing |
| AssertJ | 3.x (managed) | Apache-2.0 | Fluent assertions |
| Mockito | 5.x (managed) | MIT | Test mocks and stubs |
| Awaitility | 4.2.2 | Apache-2.0 | Async condition polling in tests |
| JaCoCo | 0.8.13 | EPL-2.0 | Code coverage measurement and enforcement |

> `jakarta.annotation-api` is dual-licensed EPL-2.0 / GPL-2.0 with Classpath Exception. The Classpath Exception permits use in proprietary commercial software without triggering GPL copyleft obligations, making it commercially safe.

> All other libraries are Apache-2.0, MIT, or BSD-3-Clause — all commercially permissive. No GPL, LGPL, or AGPL libraries are used.

## Troubleshooting

### ext_authz not enforcing (requests pass through without license check)

- Confirm the EnvoyFilter exists: `kubectl get envoyfilter license-ext-authz -n default`
- Verify the filter was pushed to the httpbin proxy: `istioctl proxy-config listener <httpbin-pod>.default --port 15006 -o json | python3 -m json.tool | grep ext_authz`
- Only sidecar-injected pods are intercepted. A pod without a sidecar (`1/1 READY`) bypasses ext_authz — this is expected.
- Confirm test pod has a sidecar: use `--labels="sidecar.istio.io/inject=true"` (label, not annotation) when running `kubectl run`.

### ext_authz fires but all requests return 403 (UAEX flag, ~250 ms latency)

Root cause: `istio.metadata_exchange` cluster filter sends a magic TCP prefix before the HTTP/2 preface for non-sidecar endpoints, corrupting gRPC framing.

Fix — confirm the DestinationRule is applied:
```bash
kubectl get destinationrule license-validation-service-notls -n default
```
If missing: `kubectl apply -f k8s/08-destinationrule.yaml`

### Liveness probe fails — `connection refused` on port 8080

`spring-boot-starter-web` must be in `pom.xml`. Without it, Spring Boot skips Tomcat startup and port 8080 is never bound. Rebuild the image after adding the dependency.

### Rules not hot-reloading

- Confirm ConfigMap is mounted: `kubectl exec deployment/license-validation-service -- ls /etc/license`
- Default poll interval: 5 s (`license.watch-interval-seconds`)
- Check logs: `kubectl logs -l app=license-validation-service | grep RuleSetWatcher`

### JaCoCo coverage gate fails

Run `mvn verify` and open `target/site/jacoco/index.html`. The gate is 85 % lines and branches. Add tests for uncovered paths — see `src/test/java/` for examples.

### Remote validator connection refused

- Confirm the backend URL in `license-rules.yml` is reachable from within the cluster
- Check for an open circuit breaker in logs: look for `CircuitBreaker` state transitions
