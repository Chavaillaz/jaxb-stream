package com.chavaillaz.jaxb.stream.namespace;

import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Data;

import static jakarta.xml.bind.annotation.XmlAccessType.FIELD;

/**
 * No explicit namespace: inherits the package's {@code urn:example:widgets} default via {@code "##default"}.
 */
@Data
@XmlAccessorType(FIELD)
@XmlRootElement(name = "widget")
public class Widget {

    @XmlElement
    private String label;

}
