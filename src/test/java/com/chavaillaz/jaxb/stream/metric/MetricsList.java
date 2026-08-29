package com.chavaillaz.jaxb.stream.metric;

import static jakarta.xml.bind.annotation.XmlAccessType.FIELD;

import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.util.List;

import lombok.experimental.Delegate;

@XmlAccessorType(FIELD)
@XmlRootElement(name = "metrics")
public class MetricsList implements List<Metric> {

    @Delegate
    private List<Metric> list;

}
