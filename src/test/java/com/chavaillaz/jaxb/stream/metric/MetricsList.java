package com.chavaillaz.jaxb.stream.metric;

import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.experimental.Delegate;

import java.util.List;

import static jakarta.xml.bind.annotation.XmlAccessType.FIELD;

@XmlAccessorType(FIELD)
@XmlRootElement(name = "metrics")
public class MetricsList implements List<Metric> {

    @Delegate
    private List<Metric> list;

}
