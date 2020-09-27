package com.chavaillaz.jaxb.stream;

import com.chavaillaz.jaxb.stream.metric.*;
import org.junit.jupiter.api.Test;

import javax.xml.bind.JAXBException;
import javax.xml.stream.XMLStreamException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.chavaillaz.jaxb.stream.metric.DiskMetric.getMetricsAllDisks;
import static org.assertj.core.api.Assertions.assertThat;

public class StreamingTest {

    public static final String FILE_NAME = "metrics.xml";

    @Test
    public void testStreaming() {
        // Given
        List<Metric> writtenMetrics = writeMetrics(FILE_NAME);

        // When
        List<Metric> readMetrics = readMetrics(FILE_NAME, DiskMetric.class, MemoryMetric.class, ProcessorMetric.class);

        // Then
        assertThat(readMetrics).isEqualTo(writtenMetrics);
    }

    private List<Metric> writeMetrics(String fileName) {
        List<Metric> metrics = new ArrayList<>();
        try (StreamingMarshaller marshaller = new StreamingMarshaller(MetricsList.class)) {
            marshaller.open(new FileOutputStream(fileName));
            writeMetrics(marshaller, metrics, DiskMetric.class, getMetricsAllDisks());
            writeMetrics(marshaller, metrics, MemoryMetric.class, new MemoryMetric());
            writeMetrics(marshaller, metrics, ProcessorMetric.class, new ProcessorMetric());
        } catch (XMLStreamException | JAXBException | IOException e) {
            throw new RuntimeException(e);
        }
        return metrics;
    }

    private <T extends Metric> void writeMetrics(StreamingMarshaller marshaller, List<Metric> list, Class<T> type, T... metrics) throws JAXBException {
        for (T metric : metrics) {
            marshaller.write(type, metric);
            list.add(metric);
        }
    }

    private List<Metric> readMetrics(String fileName, Class<?>... types) {
        List<Metric> metrics = new ArrayList<>();
        try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(types)) {
            unmarshaller.open(new FileInputStream(fileName));
            unmarshaller.iterate((type, element) -> metrics.add((Metric) element));
        } catch (XMLStreamException | JAXBException | IOException e) {
            throw new RuntimeException(e);
        }
        return metrics;
    }

}
