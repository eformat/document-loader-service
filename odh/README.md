# odh

As cluster-admin
```bash
kustomize build operator/ | oc apply -f-
```

As namespace owner
```bash
oc new-project opendatahub-trino
kustomize build . | oc apply -f-
```
