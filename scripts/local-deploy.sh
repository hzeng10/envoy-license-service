#!/usr/bin/env bash
set -euo pipefail

MVN=/opt/maven/bin/mvn
IMAGE=license-validation-service:0.1.0

echo "=== Step 1: Build and test ==="
$MVN -q clean verify

echo "=== Step 2: Build Docker image via Spring Boot Maven plugin ==="
eval "$(minikube docker-env)"
$MVN spring-boot:build-image -DskipTests \
  -Dspring-boot.build-image.imageName="$IMAGE"

echo "=== Step 3: Apply ConfigMap, Deployment, Service ==="
kubectl apply -f k8s/01-configmap.yaml
kubectl apply -f k8s/02-deployment.yaml
kubectl apply -f k8s/03-service.yaml

echo "=== Step 4: Patch Istio meshConfig with extension provider ==="
echo "IMPORTANT: Manually merge the extensionProviders block into your istio ConfigMap:"
echo "  kubectl edit cm istio -n istio-system"
echo "  (or) kubectl apply -f k8s/04-istio-extensionprovider.yaml  # overwrites entire meshConfig"
echo "  Then: kubectl rollout restart deployment/istiod -n istio-system"
echo ""
echo "  If using istioctl:"
echo "  istioctl install -y --set meshConfig.extensionProviders[0].name=license-ext-authz \\"
echo "    --set 'meshConfig.extensionProviders[0].envoyExtAuthzGrpc.service=license-validation-service.default.svc.cluster.local' \\"
echo "    --set 'meshConfig.extensionProviders[0].envoyExtAuthzGrpc.port=9191'"

echo ""
echo "=== Step 5: Apply AuthorizationPolicy ==="
kubectl apply -f k8s/05-istio-authorizationpolicy.yaml

echo ""
echo "=== Step 6: Wait for rollout ==="
kubectl rollout status deployment/license-validation-service -n default --timeout=120s

echo ""
echo "=== Done ==="
echo "Smoke test:"
echo "  kubectl run curl --image=curlimages/curl --restart=Never --rm -it -- \\"
echo "    curl -v -H 'X-License-Token: demo-token' http://httpbin.default/get"
