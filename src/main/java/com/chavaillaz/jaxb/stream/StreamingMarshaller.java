package com.chavaillaz.jaxb.stream;

import com.sun.xml.txw2.output.IndentingXMLStreamWriter;
import lombok.NonNull;
import lombok.SneakyThrows;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Marshaller;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.Closeable;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;

import static java.lang.Boolean.TRUE;
import static javax.xml.bind.Marshaller.JAXB_FRAGMENT;

/**
 * JAXB marshaller using streaming to write XML into the given output stream.
 * <p>
 * This library allows you to write a list of elements (even from different types, but with same parent) item by item.
 * The goal is to avoid loading a huge amount of data into memory when writing large files.
 * <p>
 * This marshaller works as follows:
 * <ul>
 *     <li>At instantiation, it takes the root element type defining where to store the data (XML container)</li>
 *     <li>When opening the stream, it writes the starting tag of the root element</li>
 *     <li>When writing in the stream, it marshals the given class to XML and store it</li>
 *     <li>When closing the stream, it writes the end tag of the root element</li>
 * </ul>
 * You can use it with:
 * <pre>
 *     marshaller.write(YourObject.class, new YourObject());
 * </pre>
 * Don't forget to open the stream before trying to write in it.
 */
public class StreamingMarshaller implements Closeable {

    private final Map<Class<?>, Marshaller> marshallerCache;
    private final String rootElement;
    private XMLStreamWriter xmlOut;
    private boolean isOpen;

    /**
     * Creates a new streaming marshaller writing elements in the given root element class.
     * Please note that the given class needs the {@link XmlRootElement} annotation.
     *
     * @param type The root class defining the XML container where to store the elements to write
     * @throws IllegalArgumentException if the {@link XmlRootElement} annotation is missing for the given type
     */
    public StreamingMarshaller(@NonNull Class<?> type) {
        marshallerCache = new HashMap<>();
        rootElement = getAnnotation(type, XmlRootElement.class).name();
        isOpen = false;
    }

    protected static <A extends Annotation> A getAnnotation(Class<?> type, Class<A> annotationType) {
        A annotation = type.getAnnotation(annotationType);
        if (annotation == null) {
            throw new IllegalArgumentException("Missing annotation " + annotationType + " in class " + type);
        }
        return annotation;
    }

    /**
     * Opens the given output stream in the XML file has to be written.
     * It creates the beginning of the document with XML definition and the root element.
     *
     * @param outputStream The output stream in which write the XML elements
     * @throws RuntimeException if an error was encountered while starting the XML document with the root element
     */
    @SneakyThrows
    public synchronized void open(OutputStream outputStream) {
        if (!isOpen) {
            xmlOut = new IndentingXMLStreamWriter(XMLOutputFactory.newFactory().createXMLStreamWriter(outputStream));
            xmlOut.writeStartDocument();
            xmlOut.writeStartElement(rootElement);
            isOpen = true;
        }
    }

    /**
     * Writes the given element in XML to the output stream.
     *
     * @param type   The type of the given {@code object}
     * @param object The element to marshal and write
     * @param <T>    The element type
     * @throws RuntimeException if an error was encountered while marshalling the given object
     */
    @SneakyThrows
    public synchronized <T> void write(Class<T> type, T object) {
        XmlRootElement annotation = getAnnotation(type, XmlRootElement.class);
        String objectName = annotation.name();
        JAXBElement<T> element = new JAXBElement<>(QName.valueOf(objectName), type, object);
        getMarshaller(type).marshal(element, xmlOut);
    }

    /**
     * Gets the marshaller for the given type.
     *
     * @param type The type of elements the marshaller has to handle
     * @param <T>  The element type
     * @return The marshaller handling the conversion of the given element type
     * @throws RuntimeException if an error was encountered while creating the marshaller
     */
    public <T> Marshaller getMarshaller(Class<T> type) {
        return marshallerCache.computeIfAbsent(type, this::createMarshaller);
    }

    /**
     * Creates a new marshaller for the given type.
     *
     * @param type The type of elements the marshaller has to handle
     * @return The marshaller created, capable of handling the conversion of the given element type
     * @throws RuntimeException if an error was encountered while creating the marshaller
     */
    @SneakyThrows
    public Marshaller createMarshaller(Class<?> type) {
        JAXBContext context = JAXBContext.newInstance(type);
        Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(JAXB_FRAGMENT, TRUE);
        return marshaller;
    }

    /**
     * Writes the closing tag and closes the stream.
     *
     * @throws RuntimeException if an error occur while writing the document end or while closing the stream
     */
    @Override
    @SneakyThrows
    public synchronized void close() {
        if (isOpen) {
            xmlOut.writeEndDocument();
            xmlOut.close();
            isOpen = false;
        }
    }

}