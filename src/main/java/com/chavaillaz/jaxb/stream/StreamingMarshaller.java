package com.chavaillaz.jaxb.stream;

import com.sun.xml.txw2.output.IndentingXMLStreamWriter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
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
@Slf4j
public class StreamingMarshaller implements Closeable {

    private final Map<Class<?>, Marshaller> marshallerCache = new HashMap<>();
    private final String rootElement;
    private XMLStreamWriter xmlWriter;

    /**
     * Creates a new streaming marshaller writing elements in the given root element class.
     * Please note that the given class needs the {@link XmlRootElement} annotation.
     *
     * @param type The root class defining the XML container where to store the elements to write
     * @throws IllegalArgumentException if the {@link XmlRootElement} annotation is missing for the given type
     */
    public StreamingMarshaller(@NonNull Class<?> type) {
        this.rootElement = getAnnotation(type, XmlRootElement.class).name();
    }

    /**
     * Creates a new streaming marshaller writing elements in the given root element.
     *
     * @param rootElement The root used as XML container where to store the elements to write
     */
    public StreamingMarshaller(@NonNull String rootElement) {
        this.rootElement = rootElement;
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
     * If an output stream is already open, it closes it before opening the new one.
     *
     * @param outputStream The output stream in which write the XML elements
     * @throws XMLStreamException if an error was encountered while starting the XML document with the root element
     */
    public synchronized void open(OutputStream outputStream) throws XMLStreamException {
        if (xmlWriter != null) {
            close();
        }

        xmlWriter = new IndentingXMLStreamWriter(XMLOutputFactory.newFactory().createXMLStreamWriter(outputStream));
        xmlWriter.writeStartDocument();
        xmlWriter.writeStartElement(rootElement);
    }

    /**
     * Writes the given element in XML to the output stream.
     *
     * @param type   The type of the given {@code object}
     * @param object The element to marshal and write
     * @param <T>    The element type
     * @throws JAXBException if an error was encountered while marshalling the given object
     */
    public synchronized <T> void write(Class<T> type, T object) throws JAXBException {
        XmlRootElement annotation = getAnnotation(type, XmlRootElement.class);
        String objectName = annotation.name();
        JAXBElement<T> element = new JAXBElement<>(QName.valueOf(objectName), type, object);
        getMarshaller(type).marshal(element, xmlWriter);
    }

    /**
     * Gets the marshaller for the given type.
     *
     * @param type The type of elements the marshaller has to handle
     * @param <T>  The element type
     * @return The marshaller handling the conversion of the given element type
     * @throws JAXBException if an error was encountered while creating the marshaller
     */
    public <T> Marshaller getMarshaller(Class<T> type) throws JAXBException {
        Marshaller marshaller = marshallerCache.get(type);
        if (marshaller == null) {
            marshaller = createMarshaller(type);
            marshallerCache.put(type, marshaller);
        }
        return marshaller;
    }

    /**
     * Creates a new marshaller for the given type.
     *
     * @param type The type of elements the marshaller has to handle
     * @return The marshaller created, capable of handling the conversion of the given element type
     * @throws JAXBException if an error was encountered while creating the marshaller
     */
    public Marshaller createMarshaller(Class<?> type) throws JAXBException {
        JAXBContext context = JAXBContext.newInstance(type);
        Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(JAXB_FRAGMENT, TRUE);
        return marshaller;
    }

    /**
     * Writes the closing tag and closes the stream.
     */
    @Override
    public synchronized void close() {
        try {
            if (xmlWriter != null) {
                xmlWriter.writeEndDocument();
                xmlWriter.close();
            }
        } catch (XMLStreamException e) {
            log.error("Unable to close XML stream writer", e);
        } finally {
            xmlWriter = null;
        }
    }

}