import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.config.SaslConfigs
import java.util.*

class ConsumerProperties {

    fun configureProperties() : Properties{

        val settings = Properties()
        settings.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer")
        settings.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "io.confluent.kafka.serializers.KafkaAvroDeserializer")
        val consumer_group: String = System.getenv("TARGET_CONSUMER_GROUP") ?: "<Consumer Group>"
        settings.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "$consumer_group")
        settings.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

        val api_key: String = System.getenv("CC_API_KEY") ?: "<CC Cluster API Key>"
        val api_secret: String = System.getenv("CC_API_SECRET") ?: "<CC Cluster API Secret>"
        val broker_boostrap: String = System.getenv("CC_BOOTSRAP_SERVER") ?: "<CC Cluster Bootstrap>"
        val schema_registry_url: String = System.getenv("SR_URL") ?: "<CC Schema Registry URL>"
        val schema_registry_credentials: String = System.getenv("SR_CRED") ?: "<CC Schema Register Api Key:Secret>"
        /*
        Uncomment this if you want to try the non-shared KEK flow. In that case the client needs Key Vault access.

        settings.setProperty("rule.executors._default_.param.tenant.id", System.getenv("AZURE_TENANT_ID"))
        settings.setProperty("rule.executors._default_.param.client.id", System.getenv("AZURE_CLIENT_ID"))
        settings.setProperty("rule.executors._default_.param.client.secret", System.getenv("AZURE_CLIENT_SECRET"))

         */

        settings.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "$broker_boostrap")
        settings.setProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL")
        settings.setProperty(SaslConfigs.SASL_MECHANISM, "PLAIN")

        settings.setProperty(SaslConfigs.SASL_JAAS_CONFIG, "org.apache.kafka.common.security.plain.PlainLoginModule required username='$api_key' password='$api_secret';")

        settings.setProperty("schema.registry.url", "$schema_registry_url")
        settings.setProperty("basic.auth.credentials.source", "USER_INFO")
        settings.setProperty("schema.registry.basic.auth.user.info", "$schema_registry_credentials")
        settings.setProperty("specific.avro.reader", "true")
        settings.setProperty("use.latest.version", "true")


        return settings
    }
}