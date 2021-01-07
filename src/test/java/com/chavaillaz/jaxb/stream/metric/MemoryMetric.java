package com.chavaillaz.jaxb.stream.metric;

import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.AllArgsConstructor;
import lombok.Data;

import static jakarta.xml.bind.annotation.XmlAccessType.FIELD;
import static java.lang.Runtime.getRuntime;

@Data
@AllArgsConstructor
@XmlAccessorType(FIELD)
@XmlRootElement(name = "memory")
public class MemoryMetric implements Metric {

    /**
     * The free memory within the total memory
     */
    @XmlElement(name = "freeMemory")
    private long freeMemory;

    /**
     * The maximum amount of memory available to the JVM
     */
    @XmlElement(name = "maxMemory")
    private long maxMemory;

    /**
     * The total memory allocated from the system
     * (which can at most reach the maximum memory value)
     */
    @XmlElement(name = "totalMemory")
    private long totalMemory;

    /**
     * Gets current metrics for the memory.
     */
    public MemoryMetric() {
        freeMemory = getRuntime().freeMemory();
        maxMemory = getRuntime().maxMemory();
        totalMemory = getRuntime().totalMemory();
    }

    @Override
    public String getName() {
        return "Memory metric";
    }
}
