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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

public class StreamingTest {

    public static final String FILE_NAME = "metrics.xml";
    public static final Class<?>[] TYPES = { DiskMetric.class, MemoryMetric.class, ProcessorMetric.class };

    @Test
    public void testSuccessfulWritingAndReading() {
        List<Metric> writtenMetrics = writeMetrics(FILE_NAME);
        List<Metric> readMetrics = readMetrics(FILE_NAME, TYPES);
        assertThat(readMetrics).isEqualTo(writtenMetrics);
    }

    @Test
    public void testInvalidTypeForNextElement() throws Exception {
        writeMetrics(FILE_NAME);
        try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(TYPES)) {
            unmarshaller.open(new FileInputStream(FILE_NAME));
            assertThrows(JAXBException.class, () -> {
                while (unmarshaller.hasNext()) {
                    // Wrongly expect always one type of metric
                    unmarshaller.next(DiskMetric.class);
                }
            });
        }
    }

    @Test
    public void testReadTooManyElements() throws Exception {
        List<Metric> writtenMetrics = writeMetrics(FILE_NAME);
        try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(TYPES)) {
            unmarshaller.open(new FileInputStream(FILE_NAME));
            assertThrows(XMLStreamException.class, () -> {
                // Read one more element that does not exist
                for (int i = 0; i < writtenMetrics.size() + 1; i++) {
                    readNext(unmarshaller);
                }
            });
        }
    }

    @Test
    public void testNullTypeAtInstantiation() {
        assertThrows(NullPointerException.class, () -> new StreamingMarshaller(null));
    }

    @Test
    public void testMissingXmlRootElementAnnotation() {
        assertThrows(IllegalArgumentException.class, () -> new StreamingMarshaller(Object.class));
        assertThrows(IllegalArgumentException.class, () -> new StreamingUnmarshaller(Object.class));
    }

    @Test
    public void testOpenTwice() throws Exception {
        try (StreamingMarshaller marshaller = spy(new StreamingMarshaller(MetricsList.class))) {
            marshaller.open(new FileOutputStream(FILE_NAME));
            marshaller.open(new FileOutputStream(FILE_NAME));
            verify(marshaller, times(1)).close();
        }

        try (StreamingUnmarshaller unmarshaller = spy(new StreamingUnmarshaller(DiskMetric.class))) {
            unmarshaller.open(new FileInputStream(FILE_NAME));
            unmarshaller.open(new FileInputStream(FILE_NAME));
            verify(unmarshaller, times(1)).close();
        }
    }

    @Test
    public void testCloseWithoutOpen() throws Exception {
        new StreamingMarshaller(MetricsList.class).close();
        new StreamingUnmarshaller(DiskMetric.class).close();
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

    private Object readNext(StreamingUnmarshaller unmarshaller) throws XMLStreamException, JAXBException {
        return unmarshaller.next(unmarshaller.getNextType());
    }

}
