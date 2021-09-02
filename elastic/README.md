# elastic

As cluster-admin
```bash
kustomize build operator/ | oc apply -f-
```

As namespace owner
```bash
oc new-project engagements-dev
kustomize build . | oc apply -f-
```
