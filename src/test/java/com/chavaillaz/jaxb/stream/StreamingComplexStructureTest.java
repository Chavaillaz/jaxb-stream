package com.chavaillaz.jaxb.stream;

import com.chavaillaz.jaxb.stream.metric.MemoryMetric;
import com.chavaillaz.jaxb.stream.metric.Metric;
import com.chavaillaz.jaxb.stream.metric.ProcessorMetric;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the extension points documented in the README for XML files with a more complex
 * structure, where the stream of elements to read/write is nested one level deeper than
 * the document root.
 */
class StreamingComplexStructureTest {

    public static final String FILE_NAME = "metrics-nested.xml";

    /**
     * Wraps the usual {@code metrics} container in an additional {@code envelope} element.
     */
    static class NestedMarshaller extends StreamingMarshaller {

        NestedMarshaller(String rootElement) {
            super(rootElement);
        }

        @Override
        protected void createDocumentStart() throws XMLStreamException {
            xmlWriter.writeStartDocument();
            xmlWriter.writeStartElement("envelope");
            xmlWriter.writeStartElement(rootElement);
        }

    }

    @Test
    void testWritingAndReadingNestedContainer() throws Exception {
        List<Metric> writtenMetrics = new ArrayList<>();
        try (NestedMarshaller marshaller = new NestedMarshaller("metrics")) {
            marshaller.open(new FileOutputStream(FILE_NAME));

            MemoryMetric memory = new MemoryMetric();
            marshaller.write(MemoryMetric.class, memory);
            writtenMetrics.add(memory);

            ProcessorMetric processor = new ProcessorMetric();
            marshaller.write(ProcessorMetric.class, processor);
            writtenMetrics.add(processor);
        }

        List<Metric> readMetrics = new ArrayList<>();
        Map<Class<?>, String> types = Map.of(
                MemoryMetric.class, "memory",
                ProcessorMetric.class, "processor"
        );
        try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(types)) {
            // The envelope and the metrics container both need to be skipped
            unmarshaller.open(new FileInputStream(FILE_NAME), 2);
            unmarshaller.iterate((type, element) -> readMetrics.add((Metric) element));
        }

        assertThat(readMetrics).isEqualTo(writtenMetrics);
    }

    @Test
    void testWrongSkipDepthFailsToLocateElements() throws Exception {
        try (NestedMarshaller marshaller = new NestedMarshaller("metrics")) {
            marshaller.open(new FileOutputStream(FILE_NAME));
            marshaller.write(MemoryMetric.class, new MemoryMetric());
        }

        Map<Class<?>, String> types = Map.of(MemoryMetric.class, "memory");
        try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(types)) {
            // Only the envelope is skipped, the metrics container is not
            unmarshaller.open(new FileInputStream(FILE_NAME), 1);
            assertThrows(XMLStreamException.class, unmarshaller::getNextType);
        }
    }

}
