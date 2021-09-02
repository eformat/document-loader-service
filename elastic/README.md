# elastic

As cluster-admin
```bash
kustomize build operator/ | oc apply -f-
```

As namespace owner
```bash
kustomize build . | oc apply -f-
```
