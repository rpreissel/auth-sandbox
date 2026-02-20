# Setup Guide — auth-sandbox

Step-by-step instructions to bring up the complete local environment from scratch.

---

## Prerequisites

The following tools must be installed on macOS:

```bash
brew install podman kind kubectl k9s
```

Verify:

```bash
podman --version     # >= 5.8
kind version         # >= 0.31
kubectl version --client
k9s version
```

---

## 1. Podman Machine

Create and start the Podman VM (rootful mode is required for Kind):

```bash
podman machine init auth-sandbox --cpus 4 --memory 4096 --disk-size 60
podman machine stop auth-sandbox
podman machine set --rootful auth-sandbox
podman machine start auth-sandbox
```

Set it as the active Podman connection:

```bash
podman system connection default auth-sandbox-root
```

Verify:

```bash
podman info --format '{{.Host.OS}}'
# expected: linux
```

---

## 2. Kind Cluster

Create the Kubernetes cluster using the Podman provider:

```bash
KIND_EXPERIMENTAL_PROVIDER=podman kind create cluster --name auth-sandbox
```

This automatically sets the kubectl context to `kind-auth-sandbox`.

Verify:

```bash
kubectl get nodes --context kind-auth-sandbox
# NAME                         STATUS   ROLES           AGE   VERSION
# auth-sandbox-control-plane   Ready    control-plane   ...   v1.35.x
```

---

## 3. ingress-nginx

Install the ingress controller and label the node so it can be scheduled:

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.12.1/deploy/static/provider/kind/deploy.yaml --context kind-auth-sandbox

kubectl label node auth-sandbox-control-plane ingress-ready=true --context kind-auth-sandbox

kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=120s \
  --context kind-auth-sandbox
```

---

## 4. cert-manager

Install cert-manager for automatic TLS certificate provisioning:

```bash
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml --context kind-auth-sandbox

kubectl wait --namespace cert-manager \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/instance=cert-manager \
  --timeout=120s \
  --context kind-auth-sandbox
```

---

## 5. Keycloak Stack

Apply all manifests in the `keycloak/` directory:

```bash
kubectl apply -f keycloak/namespace.yaml        --context kind-auth-sandbox
kubectl apply -f keycloak/postgres-secret.yaml  --context kind-auth-sandbox
kubectl apply -f keycloak/keycloak-secret.yaml  --context kind-auth-sandbox
kubectl apply -f keycloak/postgres.yaml         --context kind-auth-sandbox
kubectl apply -f keycloak/cluster-issuer.yaml   --context kind-auth-sandbox
kubectl apply -f keycloak/proxy-headers.yaml    --context kind-auth-sandbox
kubectl apply -f keycloak/keycloak.yaml         --context kind-auth-sandbox
kubectl apply -f keycloak/ingress.yaml          --context kind-auth-sandbox
```

Wait for all pods to be ready:

```bash
kubectl wait --namespace keycloak \
  --for=condition=ready pod --selector=app=keycloak-postgres \
  --timeout=120s --context kind-auth-sandbox

kubectl wait --namespace keycloak \
  --for=condition=ready pod --selector=app=keycloak \
  --timeout=120s --context kind-auth-sandbox
```

---

## 6. /etc/hosts

Add the following entry (one-time, requires sudo):

```bash
echo "127.0.0.1  keycloak.localhost" | sudo tee -a /etc/hosts
```

---

## 7. Access Keycloak

Start the port-forward (run in a dedicated terminal or background):

```bash
kubectl port-forward -n ingress-nginx svc/ingress-nginx-controller 8443:443 \
  --context kind-auth-sandbox
```

Open in browser: **https://keycloak.localhost:8443**

Admin credentials:
- **User:** `admin`
- **Password:** `admin-password`

> The browser will show a certificate warning (self-signed CA). Accept it to proceed.

---

## 8. Daily Workflow (after reboot)

```bash
# Start the Podman machine
podman machine start auth-sandbox

# Restore the active connection
podman system connection default auth-sandbox-root

# Start port-forward
kubectl port-forward -n ingress-nginx svc/ingress-nginx-controller 8443:443 \
  --context kind-auth-sandbox &

# Open https://keycloak.localhost:8443
```

The Kind cluster and all pods persist across reboots — no need to re-apply manifests.

---

## Monitoring with k9s

```bash
k9s --context kind-auth-sandbox -n keycloak
```

Useful commands inside k9s:

| Key | Action |
|---|---|
| `0` | All namespaces |
| `:pods` | Pod list |
| `:ingress` | Ingress list |
| `l` | Logs |
| `d` | Describe |
| `s` | Shell |
| `?` | All shortcuts |

---

## Cluster Overview

| Component | Namespace | Image |
|---|---|---|
| Keycloak | `keycloak` | `quay.io/keycloak/keycloak:26.5` |
| PostgreSQL (Keycloak) | `keycloak` | `postgres:16` |
| ingress-nginx | `ingress-nginx` | controller v1.12.1 |
| cert-manager | `cert-manager` | latest |

TLS is handled by cert-manager with a self-signed local CA (`local-ca-issuer`).
Certificates are automatically issued and renewed via the `keycloak-tls` secret.

---

## Teardown

```bash
# Delete the Kind cluster
kind delete cluster --name auth-sandbox

# Stop and delete the Podman machine
podman machine stop auth-sandbox
podman machine rm auth-sandbox
```
