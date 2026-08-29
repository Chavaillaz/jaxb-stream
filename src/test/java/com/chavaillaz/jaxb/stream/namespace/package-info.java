/**
 * Test fixtures for namespace resolution, kept in their own package so {@link jakarta.xml.bind.annotation.XmlSchema}
 * declares a package-wide default namespace that {@link Widget} inherits via {@code @XmlRootElement}'s
 * {@code "##default"} namespace, exactly like a real-world generated or hand-written namespaced schema would.
 */
@XmlSchema(namespace = "urn:example:widgets", elementFormDefault = XmlNsForm.QUALIFIED)
package com.chavaillaz.jaxb.stream.namespace;

import jakarta.xml.bind.annotation.XmlNsForm;
import jakarta.xml.bind.annotation.XmlSchema;
