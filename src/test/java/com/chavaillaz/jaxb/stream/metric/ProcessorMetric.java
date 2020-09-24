package com.chavaillaz.jaxb.stream.metric;

import com.sun.management.OperatingSystemMXBean;
import lombok.AllArgsConstructor;
import lombok.Data;

import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import static java.lang.management.ManagementFactory.getOperatingSystemMXBean;
import static javax.xml.bind.annotation.XmlAccessType.FIELD;

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
        availableProcessors = bean.getAvailableProcessors();
        systemLoad = bean.getSystemCpuLoad();
        processLoad = bean.getProcessCpuLoad();
    }

    @Override
    public String getName() {
        return "Processor metric";
    }
}
