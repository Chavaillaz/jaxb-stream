package com.chavaillaz.jaxb.stream;

import static com.chavaillaz.jaxb.stream.metric.DiskMetric.getMetricsAllDisks;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLStreamException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chavaillaz.jaxb.stream.metric.DiskMetric;
import com.chavaillaz.jaxb.stream.metric.MemoryMetric;
import com.chavaillaz.jaxb.stream.metric.Metric;
import com.chavaillaz.jaxb.stream.metric.MetricsList;
import com.chavaillaz.jaxb.stream.metric.ProcessorMetric;

@DisplayName("StreamingMarshaller and StreamingUnmarshaller")
class StreamingTest {

    public static final String FILE_NAME = "metrics.xml";
    public static final Class<?>[] TYPES = { DiskMetric.class, MemoryMetric.class, ProcessorMetric.class };

    @TempDir
    Path tempDir;

    private File file() {
        return tempDir.resolve(FILE_NAME).toFile();
    }

    private List<Metric> writeMetrics(File file) {
        List<Metric> metrics = new ArrayList<>();
        try (StreamingMarshaller marshaller = new StreamingMarshaller("metrics")) {
            marshaller.open(new FileOutputStream(file));
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

    private List<Metric> readMetrics(File file, Class<?>... types) {
        List<Metric> metrics = new ArrayList<>();
        try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(types)) {
            unmarshaller.open(new FileInputStream(file));
            unmarshaller.iterate((type, element) -> metrics.add((Metric) element));
        } catch (XMLStreamException | JAXBException | IOException e) {
            throw new RuntimeException(e);
        }
        return metrics;
    }

    private Object readNext(StreamingUnmarshaller unmarshaller) throws XMLStreamException, JAXBException {
        return unmarshaller.next(unmarshaller.getNextType());
    }

    /**
     * Always fails while creating the document start, to test the cleanup performed by {@link StreamingMarshaller}
     * when {@link StreamingMarshaller#open(OutputStream)} fails partway through.
     */
    private static class FailingMarshaller extends StreamingMarshaller {

        FailingMarshaller(String rootElement) {
            super(rootElement);
        }

        @Override
        protected void createDocumentStart() throws XMLStreamException {
            throw new XMLStreamException("Simulated failure");
        }

    }

    private static class TrackingOutputStream extends FilterOutputStream {

        private boolean closed = false;

        TrackingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void close() throws IOException {
            this.closed = true;
            super.close();
        }

    }

    private static class TrackingInputStream extends FilterInputStream {

        private boolean closed = false;

        TrackingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
            this.closed = true;
            super.close();
        }

    }

    @Nested
    @DisplayName("Writing and reading elements")
    class WritingAndReading {

        @Test
        @DisplayName("Writing then reading back several metric types gives the same data")
        void testSuccessfulWritingAndReading() {
            List<Metric> writtenMetrics = writeMetrics(file());
            List<Metric> readMetrics = readMetrics(file(), TYPES);
            assertThat(readMetrics).isEqualTo(writtenMetrics);
        }

        @Test
        @DisplayName("An empty container round-trips as zero elements, not a crash")
        void testEmptyContainerRoundTrip() throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (StreamingMarshaller marshaller = new StreamingMarshaller("metrics")) {
                marshaller.open(output);
                // No element written at all
            }

            try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(MemoryMetric.class)) {
                unmarshaller.open(new ByteArrayInputStream(output.toByteArray()));
                assertThat(unmarshaller.hasNext()).isFalse();
            }
        }

    }

    @Nested
    @DisplayName("Reading with the wrong type")
    class TypeMismatchHandling {

        @Test
        @DisplayName("Always requesting the wrong type eventually throws JAXBException")
        void testInvalidTypeForNextElement() throws Exception {
            writeMetrics(file());
            try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(TYPES)) {
                unmarshaller.open(new FileInputStream(file()));
                assertThrows(JAXBException.class, () -> {
                    while (unmarshaller.hasNext()) {
                        // Wrongly expect always one type of metric
                        unmarshaller.next(DiskMetric.class);
                    }
                });
            }
        }

        @Test
        @DisplayName("A type mismatch does not lose the stream position and can be retried")
        void testTypeMismatchIsRecoverable() throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            MemoryMetric memory = new MemoryMetric();
            ProcessorMetric processor = new ProcessorMetric();
            try (StreamingMarshaller marshaller = new StreamingMarshaller("metrics")) {
                marshaller.open(output);
                marshaller.write(MemoryMetric.class, memory);
                marshaller.write(ProcessorMetric.class, processor);
            }

            try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(MemoryMetric.class, ProcessorMetric.class)) {
                unmarshaller.open(new ByteArrayInputStream(output.toByteArray()));

                // Deliberately ask for the wrong type first
                assertThrows(JAXBException.class, () -> unmarshaller.next(ProcessorMetric.class));

                // The mismatch must not have advanced (or otherwise broken) the stream position:
                // the same element can still be read once asked for its actual type
                assertThat(unmarshaller.getNextType()).isEqualTo(MemoryMetric.class);
                assertThat(unmarshaller.next(MemoryMetric.class)).isEqualTo(memory);

                // And reading can continue normally afterward
                assertThat(unmarshaller.hasNext()).isTrue();
                assertThat(unmarshaller.next(ProcessorMetric.class)).isEqualTo(processor);
                assertThat(unmarshaller.hasNext()).isFalse();
            }
        }

        @Test
        @DisplayName("Reading past the last element throws XMLStreamException instead of returning null")
        void testReadTooManyElements() throws Exception {
            List<Metric> writtenMetrics = writeMetrics(file());
            try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(TYPES)) {
                unmarshaller.open(new FileInputStream(file()));
                assertThrows(XMLStreamException.class, () -> {
                    // Read one more element that does not exist
                    for (int i = 0; i < writtenMetrics.size() + 1; i++) {
                        readNext(unmarshaller);
                    }
                });
            }
        }

    }

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("A null root type or root element name is rejected with NullPointerException")
        void testNullTypeAtInstantiation() {
            assertAll(
                    () -> assertThrows(NullPointerException.class, () -> new StreamingMarshaller((Class<?>) null)),
                    () -> assertThrows(NullPointerException.class, () -> new StreamingMarshaller((String) null))
            );
        }

        @Test
        @DisplayName("A type without @XmlRootElement is rejected with IllegalArgumentException")
        void testMissingXmlRootElementAnnotation() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> new StreamingMarshaller(Object.class)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new StreamingUnmarshaller(Object.class))
            );
        }

    }

    @Nested
    @DisplayName("Stream lifecycle: open, write, close")
    class Lifecycle {

        @Test
        @DisplayName("Opening an already-open instance closes the previous stream first")
        void testOpenTwice() throws Exception {
            try (StreamingMarshaller marshaller = spy(new StreamingMarshaller(MetricsList.class))) {
                marshaller.open(new FileOutputStream(file()));
                marshaller.open(new FileOutputStream(file()));
                verify(marshaller, times(1)).close();
            }

            try (StreamingUnmarshaller unmarshaller = spy(new StreamingUnmarshaller(DiskMetric.class))) {
                unmarshaller.open(new FileInputStream(file()));
                unmarshaller.open(new FileInputStream(file()));
                verify(unmarshaller, times(1)).close();
            }
        }

        @Test
        @DisplayName("Closing without ever opening is a safe no-op")
        void testCloseWithoutOpen() {
            new StreamingMarshaller(MetricsList.class).close();
            new StreamingUnmarshaller(DiskMetric.class).close();
        }

        @Test
        @DisplayName("Reading before opening throws IllegalStateException")
        void testUsingUnmarshallerBeforeOpen() {
            try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(DiskMetric.class)) {
                assertThrows(IllegalStateException.class, unmarshaller::hasNext);
            }
        }

        @Test
        @DisplayName("Writing before opening throws IllegalStateException")
        void testWriteBeforeOpenThrowsIllegalState() {
            try (StreamingMarshaller marshaller = new StreamingMarshaller("metrics")) {
                assertThrows(IllegalStateException.class, () -> marshaller.write(MemoryMetric.class, new MemoryMetric()));
            }
        }

        @Test
        @DisplayName("Closing closes the underlying stream given to open()")
        void testCloseClosesUnderlyingStreams() throws Exception {
            TrackingOutputStream outputStream = new TrackingOutputStream(new FileOutputStream(file()));
            try (StreamingMarshaller marshaller = new StreamingMarshaller("metrics")) {
                marshaller.open(outputStream);
                marshaller.write(MemoryMetric.class, new MemoryMetric());
            }
            assertThat(outputStream.closed).isTrue();

            TrackingInputStream inputStream = new TrackingInputStream(new FileInputStream(file()));
            try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(MemoryMetric.class)) {
                unmarshaller.open(inputStream);
            }
            assertThat(inputStream.closed).isTrue();
        }

        @Test
        @DisplayName("A failed open() leaves the marshaller in a clean not-open state")
        void testFailedOpenLeavesMarshallerNotOpen() {
            try (FailingMarshaller marshaller = new FailingMarshaller("metrics")) {
                assertThrows(XMLStreamException.class, () -> marshaller.open(new ByteArrayOutputStream()));
                // A failed open() must leave the instance in a clean not-open state, not a broken "open" one
                assertThrows(IllegalStateException.class, () -> marshaller.write(MemoryMetric.class, new MemoryMetric()));
            }
        }

        @Test
        @DisplayName("A failed open() leaves the unmarshaller in a clean not-open state")
        void testFailedOpenLeavesUnmarshallerNotOpen() {
            String maliciousXml = "<?xml version=\"1.0\"?>\n"
                    + "<!DOCTYPE metrics [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n"
                    + "<metrics>&xxe;</metrics>";
            byte[] payload = maliciousXml.getBytes(StandardCharsets.UTF_8);

            try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(Map.of())) {
                assertThrows(XMLStreamException.class, () -> unmarshaller.open(new ByteArrayInputStream(payload)));
                // A failed open() must leave the instance in a clean not-open state, not a broken "open" one
                assertThrows(IllegalStateException.class, unmarshaller::hasNext);
            }
        }

    }

    @Nested
    @DisplayName("Lazy (un)marshaller creation")
    class LazyCreation {

        @Test
        @DisplayName("The unmarshaller for a type is only built on first use, not at instantiation")
        void testUnmarshallerCreationIsLazy() {
            // Construction succeeds even though BrokenType cannot be turned into a JAXBContext,
            // proving the unmarshaller is created lazily rather than eagerly at instantiation
            try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(BrokenType.class)) {
                assertThrows(JAXBException.class, () -> unmarshaller.getUnmarshaller(BrokenType.class));
            }
        }

        @Test
        @DisplayName("The marshaller for a type is only built on first use, not at instantiation")
        void testMarshallerCreationIsLazy() {
            try (StreamingMarshaller marshaller = new StreamingMarshaller("root")) {
                assertThrows(JAXBException.class, () -> marshaller.getMarshaller(BrokenType.class));
            }
        }

    }

    @Nested
    @DisplayName("Security")
    class Security {

        @Test
        @DisplayName("An external entity (XXE) payload is rejected")
        void testExternalEntityIsRejected() {
            String maliciousXml = "<?xml version=\"1.0\"?>\n"
                    + "<!DOCTYPE metrics [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n"
                    + "<metrics>&xxe;</metrics>";
            byte[] payload = maliciousXml.getBytes(StandardCharsets.UTF_8);

            try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(Map.of())) {
                assertThrows(XMLStreamException.class, () -> unmarshaller.open(new ByteArrayInputStream(payload)));
            }
        }

    }

    /**
     * Non-static inner class: JAXB cannot build a {@link jakarta.xml.bind.JAXBContext} for it,
     * so it is used to prove that marshaller/unmarshaller creation is deferred until first use.
     */
    @XmlRootElement(name = "broken")
    private class BrokenType {

    }

}
