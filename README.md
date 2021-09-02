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

Test
```bash
# exportFile docx format (default)
curl -vvv http://localhost:8080/gdrive/exportFile?fileId=1WIDbZg7VN8N97P_0hU5JD89ESYZKpZoMR3tNhOaeHrc
# export and entire folder of docs
curl -vvv "http://localhost:8080/gdrive/exportFolder?folderId=1myiHJY7U5WDpAzDl7xohs8tf2Yps1FIi"
```

## Run on OpenShift

Create project
```bash
oc new-project engagements-dev
```

Deploy elastic cluster
```bash
kustomize build elastic/operator | oc apply -f-
kustomize build elastic | oc apply -f-
```

Deploy application
```bash
helm repo add eformat https://eformat.github.io/helm-charts
helm upgrade --install document-loader-service eformat/document-loader-service
```
