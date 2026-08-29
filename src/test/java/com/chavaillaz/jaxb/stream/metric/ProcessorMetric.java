package com.chavaillaz.jaxb.stream.metric;

import static jakarta.xml.bind.annotation.XmlAccessType.FIELD;
import static java.lang.management.ManagementFactory.getOperatingSystemMXBean;

import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

import com.sun.management.OperatingSystemMXBean;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@XmlAccessorType(FIELD)
@XmlRootElement(name = "processor")
public class ProcessorMetric implements Metric {

    /**
     * The recent CPU usage for the whole system
     */
    @XmlElement(name = "systemLoad")
    private double systemLoad;

    /**
     * The recent CPU usage for the JVM process
     */
    @XmlElement(name = "processLoad")
    private double processLoad;

    /**
     * The number of processors available to the JVM
     */
    @XmlElement(name = "availableProcessors")
    private int availableProcessors;

    /**
     * Gets current metrics for the processor.
     */
    public ProcessorMetric() {
        OperatingSystemMXBean bean = (OperatingSystemMXBean) getOperatingSystemMXBean();
        this.availableProcessors = bean.getAvailableProcessors();
        this.systemLoad = bean.getSystemCpuLoad();
        this.processLoad = bean.getProcessCpuLoad();
    }

    @Override
    public String getName() {
        return "Processor metric";
    }

}
