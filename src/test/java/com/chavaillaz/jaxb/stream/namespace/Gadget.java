package com.chavaillaz.jaxb.stream.namespace;

import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Data;

import static jakarta.xml.bind.annotation.XmlAccessType.FIELD;

/**
 * Explicit namespace on the annotation itself, overriding the package's {@code urn:example:widgets} default.
 */
@Data
@XmlAccessorType(FIELD)
@XmlRootElement(name = "gadget", namespace = "urn:example:gadgets")
public class Gadget {

    @XmlElement
    private String label;

}
