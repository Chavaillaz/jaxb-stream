package com.chavaillaz.jaxb.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chavaillaz.jaxb.stream.namespace.Gadget;
import com.chavaillaz.jaxb.stream.namespace.Widget;

/**
 * Covers XML namespace resolution: both {@code StreamingMarshaller.write(Class, Object)} and
 * {@code StreamingUnmarshaller}'s varargs constructor have to qualify a type's tag name with its
 * effective namespace exactly like JAXB itself would, or they silently disagree with what JAXB
 * actually writes/expects on the wire.
 */
@DisplayName("XML namespace resolution")
class StreamingNamespaceTest {

    public static final String FILE_NAME = "namespaced.xml";

    @TempDir
    Path tempDir;

    private File file() {
        return tempDir.resolve(FILE_NAME).toFile();
    }

    @Test
    @DisplayName("A type's namespace, inherited from its package's @XmlSchema, is written and read correctly")
    void testPackageDefaultNamespaceRoundTrip() throws Exception {
        Widget widget = new Widget();
        widget.setLabel("hello");

        try (StreamingMarshaller marshaller = new StreamingMarshaller("widgets")) {
            marshaller.open(new FileOutputStream(file()));
            marshaller.write(Widget.class, widget);
        }

        String xml = Files.readString(file().toPath());
        assertThat(xml).contains("xmlns=\"urn:example:widgets\"");

        try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(Widget.class)) {
            unmarshaller.open(new FileInputStream(file()));
            assertThat(unmarshaller.next(Widget.class)).isEqualTo(widget);
        }
    }

    @Test
    @DisplayName("A type's own namespace overrides its package's default namespace")
    void testExplicitNamespaceOverridesPackageDefault() throws Exception {
        Gadget gadget = new Gadget();
        gadget.setLabel("world");

        try (StreamingMarshaller marshaller = new StreamingMarshaller("gadgets")) {
            marshaller.open(new FileOutputStream(file()));
            marshaller.write(Gadget.class, gadget);
        }

        // The root <gadget> element itself must be in urn:example:gadgets (as a prefixed name, since the
        // package's elementFormDefault=QUALIFIED still applies urn:example:widgets to unqualified children
        // like <label> - only the root element's own namespace is overridden here)
        String xml = Files.readString(file().toPath());
        assertThat(xml).contains("xmlns:ns2=\"urn:example:gadgets\"").contains("ns2:gadget");

        // The real test of correctness: it must round-trip through a namespace-aware unmarshaller
        try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(Gadget.class)) {
            unmarshaller.open(new FileInputStream(file()));
            assertThat(unmarshaller.next(Gadget.class)).isEqualTo(gadget);
        }
    }

    @Test
    @DisplayName("A namespaced tag name can also be given explicitly via the {namespaceURI}localName format")
    void testExplicitNamespacedTagName() throws Exception {
        Widget widget = new Widget();
        widget.setLabel("boxed");
        String taggedName = "{urn:example:custom}box";

        try (StreamingMarshaller marshaller = new StreamingMarshaller("root")) {
            marshaller.open(new FileOutputStream(file()));
            marshaller.write(Widget.class, taggedName, widget);
        }

        Map<Class<?>, String> types = Map.of(Widget.class, taggedName);
        try (StreamingUnmarshaller unmarshaller = new StreamingUnmarshaller(types)) {
            unmarshaller.open(new FileInputStream(file()));
            assertThat(unmarshaller.next(Widget.class)).isEqualTo(widget);
        }
    }

}
