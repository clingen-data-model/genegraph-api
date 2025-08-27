## ClinGen Data Exchange

The ClinGen Data Exchange is an instance of [Apache Kafka](https://kafka.apache.org/documentation/), hosted on the [Confluent Cloud](https://www.confluent.io/), which serves as a major mechanism for communication between systems in ClinGen, including the [ClinGen Website](https://clinicalgenome.org), the [Gene and Variant Curation Interfaces](https://curation.clinicalgenome.org), [Genegraph](https://genegraph.clinicalgenome.org), the [Linked Data Hub](https://ldh.clinicalgenome.org/ldh/ui/), [Evidence Repository](https://erepo.clinicalgenome.org/evrepo/), the [GPM](https://gpm.clinicalgenome.org/), and the  [Gene Tracker](https://gene-tracker.clinicalgenome.org).

Using the Data Exchange for communication allows for asychronous communication between any of these systems. Using the Data Exchange as a communication mechanism allows for more resilient applications. Unlike an API-based communication mechanism, communications using the Data Exchange do not depend on any given service being online when messages are sent, allowing for failures and downtime not to cause cascading failures in other systems. Generally, topics on the Data Exchange are set up to retain all communication indefinitely, allowing for new systems to get up-to-date, while relieving data producers of the responsibility of maintaining their own archives.

The typical policy of the Data Exchange is that any developer of these (or related) tools in the ClinGen ecosystem can request access to any topic, and can also request for new topics to be set up. There is no official policy about publishing or adhering to specific message formats or schemas, but data producers are encouraged to document and share schemas for the data they share.

### Topics

There is an [inventory of topics](https://docs.google.com/spreadsheets/d/1yuO9-IM-2MRM1AacKekNJdHRb8fl6ozxO7OFu6WPQ2Q/edit?gid=0#gid=0) maintained on Google Docs.

### Resources

The [official Kafka documentation](https://kafka.apache.org/documentation/) is the best resource for understanding configuration settings for Kafka servers and clients. The introduction offers a good overview of what Kafka is, and especially the architectural decisions behind its design.

The JavaDoc for the [KafkaConsumer](https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html) and [KafkaProducer](https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html), while they offer detail specific to the Java client, offer good detail about how to manage Kafka clients generally, especially with regard to offset management and transactions.

Confluent offers a [free Kafka e-book](https://www.confluent.io/resources/ebook/kafka-the-definitive-guide/). This book offers more examples and code for actually working with Kafka, and goes into a bit more detail about use cases and tradeoffs.
