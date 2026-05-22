# Deployment Guide — envoy-license-service

This guide deploys the license validation service to a local minikube cluster with Istio 1.30. No changes are made to the `istio-system/istio` ConfigMap or namespace labels.

---

## Prerequisites

| Tool | Required | How to verify |
|---|---|---|
| JDK 21 | Oracle or Temurin | `java -version` shows `21.x` |
| Maven 3.9.x | at `/opt/maven` | `/opt/maven/bin/mvn -v` |
| Docker | any | `docker info` |
| minikube | ≥ 1.30 | `minikube status` shows `Running` |
| kubectl | ≥ 1.28 | `kubectl config current-context` shows `minikube` |
| Istio | 1.30 | `istioctl version` shows `1.30.x` |

---

## One-time setup checks

```bash
# Confirm minikube is running
minikube status

# Confirm Istio control plane is healthy
kubectl get pods -n istio-system
# istiod-xxx should be 1/1 Running

# Confirm the sidecar injector webhook is registered
kubectl get mutatingwebhookconfiguration istio-sidecar-injector
```

---

## Step 1 — Build the JAR

```bash
/opt/maven/bin/mvn clean verify
```

This compiles the service, runs 131 unit and integration tests, and enforces the JaCoCo ≥ 85 % coverage gate. Build output: `target/envoy-license-service-0.1.0.jar`.

If the build fails at the coverage gate, run:
```bash
/opt/maven/bin/mvn verify && open target/site/jacoco/index.html
```

---

## Step 2 — Build the Docker image

```bash
# Point your local Docker CLI at minikube's daemon
eval $(minikube docker-env)

# Build the image
docker build -t license-validation-service:0.1.0 .
```

**Why `docker build` and not `mvn spring-boot:build-image`?**

The Spring Boot Maven plugin uses Paketo buildpacks, which require Docker API ≥ v1.44. minikube's built-in Docker daemon exposes API v1.24. Attempting to use the Maven plugin produces:

```
client version 1.24 is too old. Minimum supported API version is 1.44
```

The project's `Dockerfile` uses a standard multi-stage build with `eclipse-temurin:21-jre` and layered JAR extraction — it works with any Docker API version.

---

## Step 3 — Apply core Kubernetes resources

```bash
kubectl apply \
  -f k8s/01-configmap.yaml \
  -f k8s/02-deployment.yaml \
  -f k8s/03-service.yaml
```

| Manifest | What it creates |
|---|---|
| `01-configmap.yaml` | `ConfigMap/license-rules` — mounted at `/etc/license/license-rules.yml` inside the pod |
| `02-deployment.yaml` | `Deployment/license-validation-service` — 1 replica, no Istio sidecar, gRPC on 9191, actuator on 8080 |
| `03-service.yaml` | `Service/license-validation-service` — ClusterIP, ports 9191 (grpc) + 8080 (http-management) |

Wait for the pod to be ready:

```bash
kubectl rollout status deployment/license-validation-service --timeout=120s
```

Verify health:

```bash
kubectl port-forward svc/license-validation-service 8080:8080 &
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
# Expected: { "status": "UP" }
kill %1
```

---

## Step 4 — Wire Istio

```bash
kubectl apply \
  -f k8s/06-envoyfilter.yaml \
  -f k8s/08-destinationrule.yaml
```

| Manifest | What it creates |
|---|---|
| `06-envoyfilter.yaml` | `EnvoyFilter/license-ext-authz` — injects `envoy.filters.http.ext_authz` into every `SIDECAR_INBOUND` HTTP filter chain in the `default` namespace |
| `08-destinationrule.yaml` | `DestinationRule/license-validation-service-notls` — disables Istio mTLS and metadata-exchange TCP prefix for the license service (see Known Issues §5) |

> Files `k8s/04-istio-extensionprovider.yaml` and `k8s/05-istio-authorizationpolicy.yaml` are **not applied**. The EnvoyFilter approach replaces them and requires no mesh ConfigMap modification.

---

## Step 5 — Deploy httpbin test workload

```bash
kubectl apply -f k8s/07-httpbin.yaml
kubectl rollout status deployment/httpbin --timeout=90s
```

Confirm the Istio sidecar was injected — the pod must show **`2/2 READY`**:

```bash
kubectl get pod -l app=httpbin
# NAME                     READY   STATUS    RESTARTS   AGE
# httpbin-xxx-yyy          2/2     Running   0          30s
```

If it shows `1/1`, the sidecar was not injected. See Known Issue §4.

---

## Step 6 — Smoke test

Run a short-lived curl pod **with the Istio sidecar** (it must use the `sidecar.istio.io/inject: "true"` **label** — not an annotation — for the webhook to inject):

```bash
kubectl run smoke --image=curlimages/curl:latest --restart=Never \
  --labels="sidecar.istio.io/inject=true" \
  --command -- sh -c '
echo "=== TC1: valid token, matched path /api/orders ===" &&
curl -s -w "\nHTTP %{http_code}\n" -H "X-License-Token: demo-token" \
  http://httpbin.default/api/orders &&
echo "" &&
echo "=== TC2: wrong token, matched path ===" &&
curl -s -w "\nHTTP %{http_code}\n" -H "X-License-Token: bad-token" \
  http://httpbin.default/api/orders &&
echo "" &&
echo "=== TC3: no token, unmatched path /get ===" &&
curl -s -o /dev/null -w "HTTP %{http_code}\n" \
  http://httpbin.default/get
'
sleep 10
kubectl logs smoke
kubectl delete pod smoke --force
```

Expected results:

| Test | Expected HTTP | Reason |
|---|---|---|
| TC1 valid token on `/api/orders` | `404` | ext_authz **allowed** it; httpbin simply has no `/api/orders` endpoint |
| TC2 wrong token on `/api/orders` | `403` | ext_authz **denied** it with `{"code":"LICENSE_INVALID","message":"Invalid license token"}` |
| TC3 no token on `/get` | `200` | `/get` does not match any rule; `unmatchedPolicy: allow` passes it through |

---

## Configuration

### Changing the license token

Option A — environment variable in the deployment (already wired via Secret reference):

```bash
kubectl create secret generic license-secret --from-literal=token=my-production-token
# The deployment's secretKeyRef will pick this up on next pod restart
kubectl rollout restart deployment/license-validation-service
```

Option B — edit the ConfigMap (hot-reloads within 5 s):

```bash
kubectl edit configmap license-rules
# Change: expectedToken: ${LICENSE_TOKEN:demo-token}
# To:     expectedToken: my-token
```

The service polls the rules file every `license.watch-interval-seconds` (default: 5 s) and reloads atomically. Verify the reload:

```bash
kubectl logs -l app=license-validation-service | grep RuleSetWatcher
# INFO  RuleSetWatcher: Rules reloaded from '/etc/license/license-rules.yml'
```

---

## Memory tuning

The deployment requests **256 Mi** and limits at **384 Mi**.

Spring Boot + gRPC/Netty JVM memory breakdown at steady state:

| Region | Size |
|---|---|
| Heap (`-Xmx160m`) | 160 Mi |
| Metaspace (`-XX:MaxMetaspaceSize=80m`) | ≤ 80 Mi |
| Code cache (`-XX:ReservedCodeCacheSize=32m`) | ≤ 32 Mi |
| Direct buffers (`-XX:MaxDirectMemorySize=32m`) | ≤ 32 Mi |
| Thread stacks + OS overhead | ~30 Mi |
| **Total** | **~334 Mi** |

A 256 Mi **limit** causes OOMKill because the JVM non-heap regions alone (~144 Mi) leave insufficient headroom. The 384 Mi limit provides a ~50 Mi safety margin.

To further reduce memory footprint, lower `Xmx` and accept slower GC cycles, or replace ZGC with G1GC:
```
-Xms32m -Xmx128m -XX:MaxMetaspaceSize=72m -XX:ReservedCodeCacheSize=24m
```

---

## Teardown

```bash
kubectl delete \
  -f k8s/07-httpbin.yaml \
  -f k8s/08-destinationrule.yaml \
  -f k8s/06-envoyfilter.yaml \
  -f k8s/03-service.yaml \
  -f k8s/02-deployment.yaml \
  -f k8s/01-configmap.yaml
```

---

## Known issues

| # | Issue | Root cause | Fix |
|---|---|---|---|
| 1 | `spring-boot:build-image` fails with "client version 1.24 is too old" | minikube Docker API v1.24 < Paketo minimum v1.44 | Use `docker build` with the project `Dockerfile` |
| 2 | Pod OOMKilled with 256 Mi limit | Spring Boot + gRPC/Netty non-heap (~144 Mi) plus heap exceeds 256 Mi | Raise limit to 384 Mi; cap JVM regions with `-Xmx160m -XX:MaxMetaspaceSize=80m -XX:ReservedCodeCacheSize=32m -XX:MaxDirectMemorySize=32m` |
| 3 | Liveness probe: `connection refused` on port 8080 | `spring-boot-starter-web` missing from `pom.xml`; Tomcat never starts | Add `spring-boot-starter-web` dependency; rebuild image |
| 4 | Sidecar not injected (`1/1 READY`) | `sidecar.istio.io/inject: "true"` set as annotation instead of label; webhook `objectSelector` only matches labels | Move to `labels:` in pod template spec |
| 5 | All ext_authz calls time out (403 UAEX, ~250 ms) | `istio.metadata_exchange` cluster filter sends magic TCP prefix before HTTP/2 preface; Netty gRPC server stalls | Apply `k8s/08-destinationrule.yaml` (`tls.mode: DISABLE`) to suppress handshake |
