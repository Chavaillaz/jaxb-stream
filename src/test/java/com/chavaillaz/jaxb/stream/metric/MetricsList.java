package com.chavaillaz.jaxb.stream.metric;

import lombok.experimental.Delegate;

import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.List;

import static javax.xml.bind.annotation.XmlAccessType.FIELD;

@XmlAccessorType(FIELD)
@XmlRootElement(name = "metrics")
public class MetricsList implements List<Metric> {

    @Delegate
    private List<Metric> list;

}
