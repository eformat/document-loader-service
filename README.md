# document-loader-service

- Downloads documents (or folders of documents) from Google Drive
- Imports them into Elasticsearch

## Run Locally

Export in your environment:
```json
export DOWNLOAD_FOLDER=/tmp/foo
export GOOGLE_API_APPLICATION_NAME=gdrive-service/1.0
export GOOGLE_API_CLIENT_ID=<client id>
export GOOGLE_API_CLIENT_SECRET=<secret>
export GOOGLE_API_REFRESH_TOKEN=<token>
```

See [OAuth Playground](https://developers.google.com/oauthplayground/) for help with registering client and getting refresh tokens.

Run elastic stack locally
```bash
podman-compose up -d
```

Run application
```bash
mvn quarkus:dev
```

Test
```bash
# exportFile docx format (default)
curl -vvv http://localhost:8080/gdrive/exportFile?fileId=1WIDbZg7VN8N97P_0hU5JD89ESYZKpZoMR3tNhOaeHrc
# export and entire folder of docs
curl -vvv "http://localhost:8080/gdrive/exportFolder?folderId=1myiHJY7U5WDpAzDl7xohs8tf2Yps1FIi"
# let the app dowload by url (file or folder)
curl -vvv "http://localhost:8080/gdrive/export?url=https://drive.google.com/drive/folders/1yoQdWMCVcE-gvpvUM2u8dSDqMWLcL67S"
```

Load all documents (requires a JWT from lodestar)
```bash
curl -vvv XGET "http://localhost:8080/artifact/load/weeklyreports" -H 'accept: */*' -H "Authorization: Bearer ${TOKEN}"
```

## Run on OpenShift

Create project
```bash
oc new-project engagements-dev
```

Deploy elastic cluster
```bash
kustomize build elastic/operator | oc apply -f-
kustomize build elastic | oc -n engagements-dev apply -f-
```

To Login to Kibana password (user is `elastic`)
```bash
echo $(oc -n engagements-dev get secret engagements-es-elastic-user -o=jsonpath='{.data.elastic}' | base64 -d)
```

Deploy OpenDataHub and Trino:
```bash
oc new-project opendatahub-trino
kustomize build odh/operator | oc apply -f-
kustomize build odh | oc -n opendatahub-trino apply -f-
```

Create Secrets
```bash
oc -n engagements-dev create secret generic secret-document-loader-service-proxy --from-literal=session_secret=$(head /dev/urandom | tr -dc A-Za-z0-9 | head -c43)

oc -n engagements-dev create secret generic document-loader-service \
  --from-literal=elastic-password=$(oc -n engagements-dev get secret engagements-es-elastic-user -o=jsonpath='{.data.elastic}' | base64 -d) \
  --from-literal=gdrive-client-id=${GOOGLE_API_CLIENT_ID} \
  --from-literal=gdrive-secret=${GOOGLE_API_CLIENT_SECRET} \
  --from-literal=gdrive-refresh-token=${GOOGLE_API_REFRESH_TOKEN}
```

Deploy application
```bash
helm repo add eformat https://eformat.github.io/helm-charts
helm upgrade --install document-loader-service eformat/document-loader-service --namespace engagements-dev 
```
