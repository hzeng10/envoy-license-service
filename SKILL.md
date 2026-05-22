---

# Skill: local-deploy — envoy-license-service

## Overview

Builds the JAR, packages it into a Docker image inside minikube, and deploys the full license-validation stack to a local minikube + Istio 1.30 cluster. After completion, ext_authz enforcement is live: every HTTP request arriving at a sidecar-injected pod in the `default` namespace is gated by the license rules before reaching the upstream.

No changes are made to the `istio-system/istio` ConfigMap, no namespace labels are modified.

---

## Prerequisites

| Tool | Required version | Check |
|---|---|---|
| JDK | 21 | `java -version` |
| Maven | 3.9.x at `/opt/maven` | `/opt/maven/bin/mvn -v` |
| minikube | ≥ 1.30, status Running | `minikube status` |
| kubectl | ≥ 1.28, context = minikube | `kubectl config current-context` |
| Istio | 1.30 installed in cluster | `istioctl version` |

---

## Steps

### 1. Build the JAR and run tests

```bash
/opt/maven/bin/mvn -q clean verify
```

Compiles the service, runs 131 unit + integration tests, and enforces the JaCoCo ≥ 85 % coverage gate. Output: `target/envoy-license-service-0.1.0.jar`.

### 2. Build the Docker image inside minikube

```bash
eval $(minikube docker-env)
docker build -t license-validation-service:0.1.0 .
```

> **Why `docker build` instead of `mvn spring-boot:build-image`?**
> minikube exposes Docker API v1.24. Paketo buildpacks (used by the Spring Boot Maven plugin) require API ≥ v1.44. Using the `Dockerfile` directly avoids this incompatibility.

### 3. Apply core Kubernetes resources

```bash
kubectl apply \
  -f k8s/01-configmap.yaml \
  -f k8s/02-deployment.yaml \
  -f k8s/03-service.yaml

kubectl rollout status deployment/license-validation-service --timeout=120s
```

### 4. Wire Istio (EnvoyFilter + DestinationRule)

```bash
kubectl apply \
  -f k8s/06-envoyfilter.yaml \
  -f k8s/08-destinationrule.yaml
```

### 5. Deploy httpbin test workload

```bash
kubectl apply -f k8s/07-httpbin.yaml
kubectl rollout status deployment/httpbin --timeout=90s
```

Confirm the sidecar was injected (`2/2 READY`):

```bash
kubectl get pod -l app=httpbin
# NAME                       READY   STATUS    RESTARTS   AGE
# httpbin-xxx-yyy            2/2     Running   0          30s
```

### 6. Smoke test

```bash
kubectl run smoke --image=curlimages/curl:latest --restart=Never \
  --labels="sidecar.istio.io/inject=true" \
  --command -- sh -c '
echo "=== TC1: ALLOW — valid token, matched path ===" &&
curl -s -w "HTTP %{http_code}\n" -H "X-License-Token: demo-token" \
  http://httpbin.default/api/orders &&
echo "=== TC2: DENY — wrong token ===" &&
curl -s -w "HTTP %{http_code}\n" -H "X-License-Token: bad-token" \
  http://httpbin.default/api/orders &&
echo "=== TC3: ALLOW — unmatched path, no token ===" &&
curl -s -o /dev/null -w "HTTP %{http_code}\n" \
  http://httpbin.default/get
'

sleep 8
kubectl logs smoke
kubectl delete pod smoke --force
```

Expected output:

```
=== TC1: ALLOW — valid token, matched path ===
HTTP 404          ← httpbin has no /api/orders but ext_authz allowed it
=== TC2: DENY — wrong token ===
{"code":"LICENSE_INVALID","message":"Invalid license token"}HTTP 403
=== TC3: ALLOW — unmatched path, no token ===
HTTP 200
```

---

## Deployed resources

| Manifest | Kind | Name | Purpose |
|---|---|---|---|
| `01-configmap.yaml` | ConfigMap | `license-rules` | Rule set mounted at `/etc/license/license-rules.yml` |
| `02-deployment.yaml` | Deployment | `license-validation-service` | License gRPC server; no Istio sidecar |
| `03-service.yaml` | Service | `license-validation-service` | ClusterIP, ports 9191 (grpc) + 8080 (actuator) |
| `06-envoyfilter.yaml` | EnvoyFilter | `license-ext-authz` | Injects ext_authz into all SIDECAR_INBOUND filter chains |
| `07-httpbin.yaml` | Deployment + Service | `httpbin` | Test target with Istio sidecar |
| `08-destinationrule.yaml` | DestinationRule | `license-validation-service-notls` | Disables mTLS + metadata-exchange for license service |

> Files `04-istio-extensionprovider.yaml` and `05-istio-authorizationpolicy.yaml` exist in `k8s/` but are **not applied**. The EnvoyFilter approach replaces them.

---

## Known issues and fixes

### 1 — `spring-boot:build-image` fails in minikube

**Symptom:** `client version 1.24 is too old. Minimum supported API version is 1.44`

**Root cause:** minikube's Docker daemon exposes API v1.24; Paketo buildpacks require ≥ v1.44.

**Fix:** Use `docker build -t license-validation-service:0.1.0 .` with the project's `Dockerfile`.

---

### 2 — Pod OOMKilled with 256 Mi memory limit

**Symptom:** Pod restarts with `OOMKilled`, exit code 137.

**Root cause:** Spring Boot + gRPC/Netty non-heap memory (Metaspace ~80 Mi, code cache ~32 Mi, Netty direct buffers, thread stacks) plus the application heap exceeds 256 Mi.

**Fix:** Memory limit raised to 384 Mi. JVM flags in `02-deployment.yaml`:
```
-Xms64m -Xmx160m -XX:MaxMetaspaceSize=80m -XX:ReservedCodeCacheSize=32m -XX:MaxDirectMemorySize=32m
```

---

### 3 — Liveness probe fails: `connection refused` on port 8080

**Symptom:** Pod enters CrashLoopBackOff; events show `dial tcp ...:8080: connect: connection refused`.

**Root cause:** `spring-boot-starter-web` was missing from `pom.xml`. Without it, Spring Boot does not start an embedded Tomcat server and port 8080 is never bound.

**Fix:** Added `spring-boot-starter-web` to `pom.xml`. Rebuild the image after this change.

---

### 4 — Istio sidecar not injected into httpbin (pod shows `1/1` instead of `2/2`)

**Symptom:** `kubectl get pod -l app=httpbin` shows `1/1 Running`.

**Root cause:** The Istio mutating webhook's `objectSelector` matches `sidecar.istio.io/inject: "true"` as a pod **label** for unlabeled namespaces. Setting it as an annotation has no effect.

**Fix:** Move `sidecar.istio.io/inject: "true"` from `annotations` to `labels` in the pod template spec.

---

### 5 — ext_authz gRPC calls all time out (all requests return 403)

**Symptom:** All requests from sidecar pods return HTTP 403. Istio telemetry shows response flag `UAEX` and duration ≈ 250 ms (exactly the ext_authz timeout). The license service logs show no incoming requests.

**Root cause:** Istio's `istio.metadata_exchange` cluster filter (with `enable_discovery: true`) sends a binary magic prefix before the HTTP/2 preface when connecting to endpoints without `tlsMode: "istio"` metadata. The license service's Netty gRPC server does not understand this prefix and stalls, causing every gRPC call to hit the 250 ms timeout.

**Evidence:** `cx_total::1, rq_total::1, rq_timeout::1` — TCP handshake succeeded but no HTTP/2 response was produced within the timeout window.

**Fix:** `k8s/08-destinationrule.yaml` sets `trafficPolicy.tls.mode: DISABLE`. This removes all `transportSocketMatches` from the cluster and suppresses the metadata-exchange handshake, restoring plain HTTP/2 gRPC to the Netty server.

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
