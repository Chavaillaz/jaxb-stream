package com.chavaillaz.jaxb.stream;

import com.chavaillaz.jaxb.stream.metric.DiskMetric;
import com.chavaillaz.jaxb.stream.metric.MemoryMetric;
import com.chavaillaz.jaxb.stream.metric.Metric;
import com.chavaillaz.jaxb.stream.metric.ProcessorMetric;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static jakarta.xml.bind.annotation.XmlAccessType.FIELD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Iterable/Stream API, schema validation and output configuration")
class StreamingErgonomicsTest {

    public static final String FILE_NAME = "metrics-ergonomics.xml";

    private static final String ITEM_XSD = "<?xml version=\"1.0\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n" +
            "  <xs:element name=\"item\">\n" +
            "    <xs:complexType>\n" +
            "      <xs:sequence>\n" +
            "        <xs:element name=\"value\" type=\"xs:positiveInteger\"/>\n" +
            "      </xs:sequence>\n" +
            "    </xs:complexType>\n" +
            "  </xs:element>\n" +
            "</xs:schema>";

    @TempDir
    Path tempDir;

    private File file() {
        return tempDir.resolve(FILE_NAME).toFile();
    }

    @Nested
    @DisplayName("Iterable and Stream API")
    class IterationApi {

        @Test
        @DisplayName("A for-each loop reads the same elements as iterate()")
        void testForEachLoop() throws Exception {
            List<Metric> written = writeMetrics();

            List<Metric> read = new ArrayList<>();
            try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(StreamingTest.TYPES)) {
                unmarshaller.open(new FileInputStream(file()));
                for (Object element : unmarshaller) {
                    read.add((Metric) element);
                }
            }

            assertThat(read).isEqualTo(written);
        }

        @Test
        @DisplayName("stream() reads the same elements as iterate()")
        void testStream() throws Exception {
            List<Metric> written = writeMetrics();

            List<Metric> read;
            try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(StreamingTest.TYPES)) {
                unmarshaller.open(new FileInputStream(file()));
                read = unmarshaller.stream().map(Metric.class::cast).toList();
            }

            assertThat(read).isEqualTo(written);
        }

        @Test
        @DisplayName("Checked exceptions raised while streaming are wrapped as UncheckedXmlException")
        void testIteratorWrapsCheckedExceptionsAsUnchecked() throws Exception {
            writeMetrics();

            try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(DiskMetric.class)) {
                // Only DiskMetric is registered, but the file also contains MemoryMetric/ProcessorMetric
                unmarshaller.open(new FileInputStream(file()));
                assertThrows(UncheckedXmlException.class, () -> unmarshaller.stream().toList());
            }
        }

    }

    private List<Metric> writeMetrics() throws Exception {
        List<Metric> metrics = new ArrayList<>();
        try (StreamingMarshaller marshaller = new StreamingMarshaller("metrics")) {
            marshaller.open(new FileOutputStream(file()));

            DiskMetric disk = new DiskMetric();
            marshaller.write(DiskMetric.class, disk);
            metrics.add(disk);

            MemoryMetric memory = new MemoryMetric();
            marshaller.write(MemoryMetric.class, memory);
            metrics.add(memory);

            ProcessorMetric processor = new ProcessorMetric();
            marshaller.write(ProcessorMetric.class, processor);
            metrics.add(processor);
        }
        return metrics;
    }

    @Nested
    @DisplayName("XSD schema validation")
    class SchemaValidation {

        @Test
        @DisplayName("The unmarshaller rejects elements invalid against a given schema")
        void testSchemaValidationRejectsInvalidElement() throws Exception {
            Schema schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                    .newSchema(new StreamSource(new StringReader(ITEM_XSD)));

            byte[] validXml = "<metrics><item><value>5</value></item></metrics>".getBytes(StandardCharsets.UTF_8);
            byte[] invalidXml = "<metrics><item><value>-5</value></item></metrics>".getBytes(StandardCharsets.UTF_8);

            // Without a schema, data violating the XSD constraint still unmarshals successfully
            try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(Item.class)) {
                unmarshaller.open(new ByteArrayInputStream(invalidXml));
                assertThat(unmarshaller.next(Item.class).value).isEqualTo(-5);
            }

            // With a schema, valid data still unmarshals successfully
            try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(Item.class)) {
                unmarshaller.setSchema(schema);
                unmarshaller.open(new ByteArrayInputStream(validXml));
                assertThat(unmarshaller.next(Item.class).value).isEqualTo(5);
            }

            // With a schema, data violating the XSD constraint is rejected
            try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(Item.class)) {
                unmarshaller.setSchema(schema);
                unmarshaller.open(new ByteArrayInputStream(invalidXml));
                assertThrows(JAXBException.class, () -> unmarshaller.next(Item.class));
            }
        }

        @Test
        @DisplayName("The marshaller rejects elements invalid against a given schema")
        void testMarshallerSchemaValidationRejectsInvalidElement() throws Exception {
            Schema schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                    .newSchema(new StreamSource(new StringReader(ITEM_XSD)));

            try (StreamingMarshaller marshaller = new StreamingMarshaller("metrics")) {
                marshaller.setSchema(schema);
                marshaller.open(new ByteArrayOutputStream());
                Item invalid = new Item();
                invalid.value = -5;
                assertThrows(JAXBException.class, () -> marshaller.write(Item.class, "item", invalid));
            }
        }

    }

    @Nested
    @DisplayName("Output configuration")
    class OutputConfiguration {

        @Test
        @DisplayName("setCharset() changes the encoding of the written XML")
        void testCharsetConfiguration() throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (StreamingMarshaller marshaller = new StreamingMarshaller("metrics")) {
                marshaller.setCharset(StandardCharsets.ISO_8859_1);
                marshaller.open(output);
                marshaller.write(MemoryMetric.class, new MemoryMetric());
            }

            String xml = output.toString(StandardCharsets.ISO_8859_1);
            assertThat(xml).contains("encoding='ISO-8859-1'");
        }

        @Test
        @DisplayName("setPrettyPrint(false) produces compact, non-indented XML")
        void testPrettyPrintDisabled() throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (StreamingMarshaller marshaller = new StreamingMarshaller("metrics")) {
                marshaller.setPrettyPrint(false);
                marshaller.open(output);
                marshaller.write(MemoryMetric.class, new MemoryMetric());
            }

            String xml = output.toString(StandardCharsets.UTF_8);
            assertThat(xml).doesNotContain("\n    ");
        }

    }

    @XmlAccessorType(FIELD)
    @XmlRootElement(name = "item")
    public static class Item {

        @XmlElement(name = "value")
        public int value;

    }

}
