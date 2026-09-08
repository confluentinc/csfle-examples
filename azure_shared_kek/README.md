# Client-Side Field Level Encryption (CSFLE) with Azure Key Vault, KMS access shared with Confluent

This repository provides a step-by-step demo of the Confluent Cloud feature [Client-Side Field Level Encryption](https://docs.confluent.io/cloud/current/security/encrypt/csfle/overview.html).

This example implements the CSFLE shared KEK flow, meaning that the DEK Registry has access to the KMS. In this scenario:
* clients do not need KMS credentials to retrieve the master key and decrypt the DEKs; the DEK Registry makes the decrypted DEK available to authorized clients
* Confluent components such as ksqlDB and Flink can decrypt fields for further processing. Please note that ksqlDB and Flink support for CSFLE is not available at the moment

## Prerequisites

* Confluent Cloud cluster with Advanced Stream Governance package
* For clients, Confluent Platform 7.4.5, 7.5.4, 7.6.1 or higher are required.
* An Azure Key Vault key and a Microsoft Entra app registration (or user-assigned managed identity)

## Goal

We will produce personal data to Confluent Cloud in the following form
```
{
    "id": "0",
    "name": "Anna",
    "birthday": "1993-08-01",
    "timestamp": "2023-10-07T19:54:21.884Z"
}
```
We encrypt the `birthday` field. A consumer with the same Schema Registry configuration decrypts it again. Neither client is given Azure credentials.

## Create Tag

Create the tag you will encrypt on, such as `PII`, in Stream Catalog first. See the [Data Contracts documentation](https://docs.confluent.io/platform/current/schema-registry/fundamentals/data-contracts.html#tags). Confluent Cloud rejects inline `confluent:tags` until that definition exists.

```shell
curl --request POST \
  --url "${SR_URL}/catalog/v1/types/tagdefs" \
  --header "Authorization: Basic ${SR_BASE64_CRED}" \
  --header "Content-Type: application/json" \
  --data '[{"entityTypes":["sr_field"],"name":"PII","description":"Personally identifiable information"}]'
```

## Azure Key Vault

Create a Key Vault and a software-protected key that supports encrypt and decrypt. Copy the key identifier (`https://<vault>.vault.azure.net/keys/<key-name>/<version>`). You will register that URI as the KEK.

Clients do not need Key Vault permissions for the shared KEK flow. Grant those permissions only if you also want to test [toggling master key sharing](#toggling-master-key-sharing).

## Preliminary parameters configuration

All CLI commands and Kotlin applications in this tutorial read OS environment variables. Create a `.env` file in this repo base path:

```shell
cat <<EOF >> .env
#!/bin/sh
export TARGET_TOPIC=csfle-kek-shared-demo
export TARGET_CONSUMER_GROUP=csfle-cg
export CC_API_KEY=<CONFLUENT_API_KEY>
export CC_API_SECRET=<CONFLUENT_API_SECRET>
export CC_BOOTSRAP_SERVER=<CONFLUENT_BOOTSTRAP_URL>
export SR_URL=https://<SR_BASE_URL>
export SR_CRED=<SR_API_KEY:SR_API_SECRET>
export SR_BASE64_CRED=<CREATE THIS BY RUNNING echo -n "<SR_API_KEY:SR_API_SECRET>" | base64>
export AZURE_KEK_NAME=<SHARED_KEK_NAME>
export AZURE_KMS_KEY_ID=<KEY_VAULT_KEY_IDENTIFIER>
export AZURE_TENANT_ID=<ENTRA_TENANT_ID>
export AZURE_CLIENT_ID=<APP_REGISTRATION_CLIENT_ID>
export AZURE_SUBSCRIPTION=<AZURE_SUBSCRIPTION_NAME_OR_ID>
export RESOURCE_GROUP=<RESOURCE_GROUP>
export LOCATION=<KEY_VAULT_REGION>
export OWNER_EMAIL=<EMAIL_REQUIRED_BY_SUBSCRIPTION_POLICY>
export KEY_VAULT_NAME=<KEY_VAULT_NAME>
export CONFLUENT_OIDC_ISSUER=<ISSUER_FROM_CONFLUENT_CLOUD_UI>
export CONFLUENT_OIDC_SUBJECT=<SUBJECT_FROM_CONFLUENT_CLOUD_UI>
export FEDERATED_CREDENTIAL_NAME=fc-confluent-keyvault-access
EOF
```

Copy the issuer and subject from **Confluent Cloud → Schema Registry → Encryption keys → Add encryption key → Azure → Share key access**. Use them exactly as shown, including a trailing slash on the issuer if present.

Source the env variables before running commands in each terminal:

```shell
source .env
```

## Register Schema

Register the schema with `PII` on the birthday field.

```shell
curl --request POST \
  --url "${SR_URL}/subjects/${TARGET_TOPIC}-value/versions" \
  --header "Authorization: Basic ${SR_BASE64_CRED}" \
  --header "Content-Type: application/vnd.schemaregistry.v1+json" \
  --data '{
            "schemaType": "AVRO",
            "schema": "{  \"name\": \"PersonalData\", \"type\": \"record\", \"namespace\": \"examples\", \"fields\": [{\"name\": \"id\", \"type\": \"string\"}, {\"name\": \"name\", \"type\": \"string\"},{\"name\": \"birthday\", \"type\": \"string\", \"confluent:tags\": [ \"PII\"]},{\"name\": \"timestamp\",\"type\": [\"string\", \"null\"]}]}"
    }'
```

## Registering KEK

Register a shared KEK so encryption rules can reference it. `shared: true` tells Schema Registry to call Azure with the federated identity.

```shell
curl --request POST \
  --url "${SR_URL}/dek-registry/v1/keks" \
  --header "Authorization: Basic ${SR_BASE64_CRED}" \
  --header 'Content-Type: application/vnd.schemaregistry.v1+json' \
  --data "{
    \"name\": \"${AZURE_KEK_NAME}\",
    \"kmsType\": \"azure-kms\",
    \"kmsKeyId\": \"${AZURE_KMS_KEY_ID}\",
    \"kmsProps\": {
      \"azure.tenant.id\": \"${AZURE_TENANT_ID}\",
      \"azure.client.id\": \"${AZURE_CLIENT_ID}\"
    },
    \"shared\": true
}"
```

You can also create this key in the Confluent Cloud Console.

## Register Encryption Rules

Then register the rule in Schema Registry. Alternatively, use the Confluent Cloud **Encryption Rules** UI.

```shell
curl --request POST \
  --url "${SR_URL}/subjects/${TARGET_TOPIC}-value/versions" \
  --header "Authorization: Basic ${SR_BASE64_CRED}" \
  --header 'Content-Type: application/vnd.schemaregistry.v1+json' \
  --data "{
        \"ruleSet\": {
        \"domainRules\": [
      {
        \"name\": \"encryptPII\",
        \"kind\": \"TRANSFORM\",
        \"type\": \"ENCRYPT\",
        \"mode\": \"WRITEREAD\",
        \"tags\": [\"PII\"],
        \"params\": {
           \"encrypt.kek.name\": \"${AZURE_KEK_NAME}\"
          },
        \"onFailure\": \"ERROR,NONE\"
        }
        ]
      }
    }"
```

Confirm the subject:

```shell
curl --url "${SR_URL}/subjects/${TARGET_TOPIC}-value/versions/latest" \
  --header "Authorization: Basic ${SR_BASE64_CRED}" | jq
```

## Allowing the DEK Registry to access Key Vault

Schema Registry assumes your Entra app through workload identity federation. Do this in Azure before the shared KEK will work. This vault uses access policies. If the vault uses Azure RBAC instead, follow the user-assigned managed identity and custom role steps shown in the Confluent Cloud Console.

### Sign in and select the subscription

```shell
az login --tenant "$AZURE_TENANT_ID"
az account set --subscription "$AZURE_SUBSCRIPTION"
```

### Create a resource group

Needed for new Azure resources in this flow. Some subscriptions deny group creation unless required tags are present.

```shell
az group create \
  --name "$RESOURCE_GROUP" \
  --location "$LOCATION" \
  --tags owner_email="$OWNER_EMAIL"
```

### Grant the app access to the key

Schema Registry must read the key and use it to encrypt and decrypt DEKs. Skip this if the app already has these permissions.

```shell
SP_OBJECT_ID=$(az ad sp show --id "$AZURE_CLIENT_ID" --query id -o tsv)

az keyvault set-policy \
  --name "$KEY_VAULT_NAME" \
  --object-id "$SP_OBJECT_ID" \
  --key-permissions get encrypt decrypt wrapKey unwrapKey
```

### Create the federated credential

This is the trust step. Confluent presents an OIDC token from the Schema Registry workload. Azure exchanges it for a token for your app. No client secret is stored in Confluent Cloud.

```shell
az ad app federated-credential create \
  --id "$AZURE_CLIENT_ID" \
  --parameters "{
    \"name\": \"${FEDERATED_CREDENTIAL_NAME}\",
    \"issuer\": \"${CONFLUENT_OIDC_ISSUER}\",
    \"subject\": \"${CONFLUENT_OIDC_SUBJECT}\",
    \"audiences\": [\"api://AzureADTokenExchange\"]
  }"
```

## Run the Producer

Create topic `${TARGET_TOPIC}` first, then continuously produce encrypted records:

```
cd KafkaProducer
./gradlew run
```

Successful logs look like:

```shell
11:17:33.077 [Thread-0] INFO  KafkaProducer - Kafka Producer started
11:17:34.495 [kafka-producer-network-thread | producer-1] INFO  KafkaProducer - event produced to csfle-kek-shared-demo
```

`birthday` is ciphertext on the wire. The other fields stay plaintext.

## Run the Consumer (Gradle)

```
cd KafkaConsumer
./gradlew run
```

It may take a few seconds, then events appear with `birthday` decrypted:

```shell
[main] INFO  KafkaConsumer - We consumed the event {"id": "2", "name": "Peter", "birthday": "1993-02-22", "timestamp": "2023-11-28T17:01:58.210Z"}
[main] INFO  KafkaConsumer - We consumed the event {"id": "3", "name": "Homer", "birthday": "1994-04-16", "timestamp": "2023-11-28T17:02:00.213Z"}
```

## Run the kafka-avro-console-consumer

```shell
kafka-avro-console-consumer --topic ${TARGET_TOPIC}  --bootstrap-server   ${CC_BOOTSRAP_SERVER}   --property schema.registry.url=${SR_URL} --property basic.auth.user.info="${SR_CRED}" --property basic.auth.credentials.source=USER_INFO --from-beginning --consumer-property security.protocol=SASL_SSL --consumer-property sasl.mechanism=PLAIN --consumer-property sasl.jaas.config="org.apache.kafka.common.security.plain.PlainLoginModule required username=\"${CC_API_KEY}\" password=\"${CC_API_SECRET}\";"
```

## Toggling Master Key sharing

KMS access for Confluent can be enabled or disabled.

When sharing is disabled, the DEK Registry no longer unwraps DEKs. Clients receive the encrypted DEK and cannot decrypt fields unless they have Azure credentials configured. Records can still be consumed because the rule uses `NONE` as the read failure action.

Disable sharing:

```shell
curl --request PUT \
  --url "${SR_URL}/dek-registry/v1/keks/${AZURE_KEK_NAME}" \
  --header "Authorization: Basic ${SR_BASE64_CRED}" \
  --header 'Content-Type: application/vnd.schemaregistry.v1+json' \
  --data '{ "shared": false}'
```

To decrypt after sharing is disabled, give the client Key Vault credentials (`AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`) and uncomment the `rule.executors` settings in `ProducerProperties.kt` / `ConsumerProperties.kt`.

Share the master key with Confluent again:

```shell
curl --request PUT \
  --url "${SR_URL}/dek-registry/v1/keks/${AZURE_KEK_NAME}" \
  --header "Authorization: Basic ${SR_BASE64_CRED}" \
  --header 'Content-Type: application/vnd.schemaregistry.v1+json' \
  --data '{ "shared": true}'
```

Then unset the Azure client credentials. The client receives the decrypted DEK from Schema Registry again.
