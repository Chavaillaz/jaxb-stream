package com.chavaillaz.jaxb.stream.metric;

import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.File;
import java.util.Arrays;

import static jakarta.xml.bind.annotation.XmlAccessType.FIELD;

@Data
@AllArgsConstructor
@XmlAccessorType(FIELD)
@XmlRootElement(name = "disk")
public class DiskMetric implements Metric {

    /**
     * The partition disk path
     */
    @XmlElement(name = "disk")
    private String disk;

    /**
     * The number of unallocated bytes in the partition
     */
    @XmlElement(name = "freePartitionSpace")
    private long freePartitionSpace;

    /**
     * The number of bytes available to this virtual machine on the partition
     */
    @XmlElement(name = "usablePartitionSpace")
    private long usablePartitionSpace;

    /**
     * The total size of the partition
     */
    @XmlElement(name = "totalCapacity")
    private long totalCapacity;

    /**
     * Gets current metrics for the current folder.
     */
    public DiskMetric() {
        this(new File("."));
    }

    /**
     * Gets currenet metrics for the given partition folder.
     *
     * @param diskPartition The partition folder
     */
    public DiskMetric(File diskPartition) {
        disk = diskPartition.getPath();
        totalCapacity = diskPartition.getTotalSpace();
        freePartitionSpace = diskPartition.getFreeSpace();
        usablePartitionSpace = diskPartition.getUsableSpace();
    }

    /**
     * Gets current metrics for all the known system partitions.
     *
     * @return The list of metrics for all partitions
     */
    public static DiskMetric[] getMetricsAllDisks() {
        return Arrays.stream(File.listRoots())
                .map(DiskMetric::new)
                .toArray(DiskMetric[]::new);
    }

    @Override
    public String getName() {
        return "Disk metrics";
    }

}
