package com.chavaillaz.jaxb.stream;

import com.chavaillaz.jaxb.stream.metric.MemoryMetric;
import com.chavaillaz.jaxb.stream.metric.Metric;
import com.chavaillaz.jaxb.stream.metric.ProcessorMetric;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the extension points documented in the README for XML files with a more complex
 * structure, where the stream of elements to read/write is nested one level deeper than
 * the document root, as well as {@code skipDepth} edge values (zero, or deeper than the
 * document actually is).
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
            this.xmlWriter.writeStartDocument();
            this.xmlWriter.writeStartElement("envelope");
            this.xmlWriter.writeStartElement(this.rootElement);
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

    @Test
    void testExcessiveSkipDepthLeavesUnmarshallerNotOpen() throws Exception {
        // Only 2 levels of nesting, no text content to trip nextTag() early -
        // forces it to run past the actual end of the document
        String xml = "<a><b><c/></b></a>";
        byte[] payload = xml.getBytes(StandardCharsets.UTF_8);

        try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(Map.of())) {
            assertThrows(XMLStreamException.class, () -> unmarshaller.open(new ByteArrayInputStream(payload), 10));
            // A failed open() must leave the instance in a clean not-open state, not a broken "open" one
            assertThrows(IllegalStateException.class, unmarshaller::hasNext);
        }
    }

    @Test
    void testSkipDepthZeroTreatsRootElementAsFirstItem() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (StreamingMarshaller marshaller = new StreamingMarshaller("metrics")) {
            marshaller.open(output);
            marshaller.write(MemoryMetric.class, new MemoryMetric());
        }

        try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(MemoryMetric.class)) {
            // With skipDepth 0, the container itself is never skipped, so the reader stays
            // positioned on the root element ("metrics"), which is not a registered type
            unmarshaller.open(new ByteArrayInputStream(output.toByteArray()), 0);
            assertThat(unmarshaller.hasNext()).isTrue();
            assertThrows(XMLStreamException.class, unmarshaller::getNextType);
        }
    }

}
