package com.chavaillaz.jaxb.stream;

import static com.chavaillaz.jaxb.stream.StreamingTest.FILE_NAME;
import static java.lang.Runtime.getRuntime;
import static java.lang.management.ManagementFactory.getOperatingSystemMXBean;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.xml.bind.annotation.XmlRootElement;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.management.OperatingSystemMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chavaillaz.jaxb.stream.schema.DiskType;
import com.chavaillaz.jaxb.stream.schema.MemoryType;
import com.chavaillaz.jaxb.stream.schema.Metrics;
import com.chavaillaz.jaxb.stream.schema.ProcessorType;

/**
 * This tests uses the generated classes from the XSD available in test resources.
 * It's another example how to use the library, instead of JAXB classes manually defined.
 * <p>
 * Please note that because of the limitation of the XJC tool used to generate classes
 * (it is not possible to add {@link XmlRootElement} annotations to all elements),
 * you have to specify the XML tag names when writing to or reading from an XML file.
 */
@DisplayName("Streaming with XJC-generated classes")
class StreamingXsdTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Writing then reading back metrics generated from an XSD schema gives the same data")
    void testSuccessfulWritingAndReadingFromXsd() throws Exception {
        File file = tempDir.resolve(FILE_NAME).toFile();
        List<Object> writtenMetrics = writeMetrics(file);
        List<Object> readMetrics = readMetrics(file);

        assertThat(writtenMetrics).usingRecursiveComparison()
                .withEqualsForType(Double::equals, Double.class)
                .withEqualsForType(Long::equals, Long.class)
                .ignoringAllOverriddenEquals()
                .ignoringCollectionOrder()
                .isEqualTo(readMetrics);
    }

    private List<Object> writeMetrics(File file) throws Exception {
        try (StreamingMarshaller marshaller = new StreamingMarshaller(Metrics.class)) {
            marshaller.open(new FileOutputStream(file));

            MemoryType memoryMetric = new MemoryType();
            memoryMetric.setFreeMemory(getRuntime().freeMemory());
            memoryMetric.setMaxMemory(getRuntime().maxMemory());
            memoryMetric.setTotalMemory(getRuntime().totalMemory());
            marshaller.write(MemoryType.class, "memory", memoryMetric);

            ProcessorType processorMetric = new ProcessorType();
            OperatingSystemMXBean bean = (OperatingSystemMXBean) getOperatingSystemMXBean();
            processorMetric.setAvailableProcessors(bean.getAvailableProcessors());
            processorMetric.setSystemLoad(bean.getSystemCpuLoad());
            processorMetric.setProcessLoad(bean.getProcessCpuLoad());
            marshaller.write(ProcessorType.class, "processor", processorMetric);

            return List.of(memoryMetric, processorMetric);
        }
    }

    private List<Object> readMetrics(File file) throws Exception {
        List<Object> metrics = new ArrayList<>();
        Map<Class<?>, String> types = Map.of(
                DiskType.class, "disk",
                MemoryType.class, "memory",
                ProcessorType.class, "processor"
        );

        try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(types)) {
            unmarshaller.open(new FileInputStream(file));
            unmarshaller.iterate((type, element) -> metrics.add(element));
        }

        return metrics;
    }

}
