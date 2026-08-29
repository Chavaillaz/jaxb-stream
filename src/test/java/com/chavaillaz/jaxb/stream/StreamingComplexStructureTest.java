package com.chavaillaz.jaxb.stream;

import com.chavaillaz.jaxb.stream.metric.MemoryMetric;
import com.chavaillaz.jaxb.stream.metric.Metric;
import com.chavaillaz.jaxb.stream.metric.ProcessorMetric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
@DisplayName("Complex XML structure and skipDepth handling")
class StreamingComplexStructureTest {

    public static final String FILE_NAME = "metrics-nested.xml";

    @TempDir
    Path tempDir;

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

    /**
     * Reimplements {@link #skipDocumentStart(int)} using direct access to the protected {@code xmlReader}
     * field, proving the unmarshaller's skip extension point is as capable as the marshaller's
     * {@code createDocumentStart()}, which already relies on the protected {@code xmlWriter} field.
     */
    static class CustomSkipUnmarshaller extends StreamingUnmarshaller {

        CustomSkipUnmarshaller(Map<Class<?>, String> types) {
            super(types);
        }

        @Override
        protected void skipDocumentStart(int skipDepth) throws XMLStreamException {
            super.skipDocumentStart(skipDepth);
            // Only possible because xmlReader is protected, like xmlWriter on StreamingMarshaller
            if (this.xmlReader == null || !this.xmlReader.hasNext()) {
                throw new XMLStreamException("Expected the reader to be usable after skipping the document start");
            }
        }

    }

    private File file() {
        return tempDir.resolve(FILE_NAME).toFile();
    }

    @Test
    @DisplayName("Reading a container nested one level deeper than the root works with the matching skipDepth")
    void testWritingAndReadingNestedContainer() throws Exception {
        List<Metric> writtenMetrics = new ArrayList<>();
        try (NestedMarshaller marshaller = new NestedMarshaller("metrics")) {
            marshaller.open(new FileOutputStream(file()));

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
            unmarshaller.open(new FileInputStream(file()), 2);
            unmarshaller.iterate((type, element) -> readMetrics.add((Metric) element));
        }

        assertThat(readMetrics).isEqualTo(writtenMetrics);
    }

    @Test
    @DisplayName("skipDocumentStart() can use the protected xmlReader field directly, like createDocumentStart() can with xmlWriter")
    void testSkipDocumentStartCanAccessXmlReaderDirectly() throws Exception {
        MemoryMetric memory = new MemoryMetric();
        try (NestedMarshaller marshaller = new NestedMarshaller("metrics")) {
            marshaller.open(new FileOutputStream(file()));
            marshaller.write(MemoryMetric.class, memory);
        }

        Map<Class<?>, String> types = Map.of(MemoryMetric.class, "memory");
        try (CustomSkipUnmarshaller unmarshaller = new CustomSkipUnmarshaller(types)) {
            unmarshaller.open(new FileInputStream(file()), 2);
            assertThat(unmarshaller.hasNext()).isTrue();
            assertThat(unmarshaller.next(MemoryMetric.class)).isEqualTo(memory);
        }
    }

    @Test
    @DisplayName("A skipDepth too shallow for a nested container fails to locate the elements")
    void testWrongSkipDepthFailsToLocateElements() throws Exception {
        try (NestedMarshaller marshaller = new NestedMarshaller("metrics")) {
            marshaller.open(new FileOutputStream(file()));
            marshaller.write(MemoryMetric.class, new MemoryMetric());
        }

        Map<Class<?>, String> types = Map.of(MemoryMetric.class, "memory");
        try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(types)) {
            // Only the envelope is skipped, the metrics container is not
            unmarshaller.open(new FileInputStream(file()), 1);
            assertThrows(XMLStreamException.class, unmarshaller::getNextType);
        }
    }

    @Test
    @DisplayName("A skipDepth deeper than the document fails to open and leaves the unmarshaller not-open")
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
    @DisplayName("A skipDepth of zero treats the root element itself as the first item")
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
