# JAXB Streaming
[![Build Status](https://travis-ci.org/Chavaillaz/jaxb-stream.svg?branch=master)](https://travis-ci.org/Chavaillaz/jaxb-stream)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.chavaillaz%3Ajaxb-stream&metric=alert_status)](https://sonarcloud.io/dashboard?id=com.chavaillaz%3Ajaxb-stream)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.chavaillaz/jaxb-stream/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.chavaillaz/jaxb-stream)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

This library allows you to read and write a list of elements 
(even from different types, but with the same parent) item by item from and to an XML file. 
The goal is to avoid loading a huge amount of data into memory when processing large files.

## Installation
The dependency is available in maven central (see badge for version):
```xml
<dependency>
    <groupId>com.chavaillaz</groupId>
    <artifactId>jaxb-stream</artifactId>
</dependency>
```

## Usage
You can find the following example in the ```StreamingTest``` class.

### Context
You are storing different types of metrics in an XML file. Because of memory constraints and the number of entries, 
you cannot load them at once as it would be done with JAXB by unmarshalling the file into the container class.

In order to process them anyway, you can use this library to read or write them, item by item.

In this example, an interface `Metric` is implemented by multiple metric types:
- Disk metrics (class `DiskMetric`, XML element `disk`)
- Memory metrics (class `MemoryMetric`, XML element `memory`)
- Processor metrics (class `ProcessorMetric`, XML element `processor`)

Each metric defines an XML element by using the annotation `@XmlRootElement`.
Those metrics would usually be stored in the container `MetricsList`, representing a list of metrics (container).
This list also defines an XML element, in that case `metrics`, the XML tag for that container.

### Writing elements
For example, to write two metrics (memory and processor metrics), the following code can be used:
```java
try (StreamingMarshaller marshaller = new StreamingMarshaller(MetricsList.class)) {
    marshaller.open(new FileOutputStream(fileName));
    marshaller.write(MemoryMetric.class, new MemoryMetric());
    marshaller.write(ProcessorMetric.class, new ProcessorMetric());
    ...
}
```

### Reading elements
For example, to read the written metrics (memory and processor metrics), the following code can be used:
```java
try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(MemoryMetric.class, ProcessorMetric.class)) {
    unmarshaller.open(new FileInputStream(fileName));
    unmarshaller.iterate((type, element) -> doWhatYouWant(element));
}
```
or by iterating over each element by yourself:
```java
try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(MemoryMetric.class, ProcessorMetric.class)) {
    unmarshaller.open(new FileInputStream(fileName));
    while (unmarshaller.hasNext()) {
        doWhatYouWant(unmarshaller.next(YourObject.class));
    }
}
```

## Contributing
If you have a feature request or found a bug, you can:

- Write an issue
- Create a pull request

If you want to contribute then

- Please write tests covering all your changes
- Ensure you didn't break the build by running `mvn test`
- Fork the repo and create a pull request

## License
This project is under Apache 2.0 License.