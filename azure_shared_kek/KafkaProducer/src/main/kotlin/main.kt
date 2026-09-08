import examples.PersonalData

fun main() {

    val kafkaProducer = KafkaProducer()
    val properties = ProducerProperties()

    val sensorData = PersonalData::class.java.getResource("/personalData.txt").readText().split("\n").filter { it.isNotBlank() }

    while (true) {
        val threadKafkaProducer = kafkaProducer.produceEvents(properties, sensorData)
        threadKafkaProducer.join()
    }
}
