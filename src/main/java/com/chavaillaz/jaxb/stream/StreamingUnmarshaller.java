package com.chavaillaz.jaxb.stream;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.extern.slf4j.Slf4j;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.Closeable;
import java.io.InputStream;
import java.util.*;
import java.util.function.BiConsumer;

import static com.chavaillaz.jaxb.stream.StreamingMarshaller.getAnnotation;
import static javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES;
import static javax.xml.stream.XMLInputFactory.SUPPORT_DTD;
import static javax.xml.stream.XMLStreamConstants.*;

/**
 * JAXB unmarshaller using streaming to read XML from the given output stream.
 * <p>
 * This library allows you to extract a list of elements (even from different types, but with same parent) item by item.
 * The goal is to avoid loading a huge amount of data into memory when writing large files.
 * <p>
 * This unmarshaller works the following way:
 * <ul>
 *     <li>At instantiation, it takes the types of elements to be read (not the root element)</li>
 *     <li>When opening the stream, it reads (ignore) the starting tag of the root element</li>
 *     <li>When getting the next stream element, it unmarshals it from XML to the given object type</li>
 * </ul>
 * You can use with the {@link #next(Class)} method:
 * <pre>
 *     while (unmarshaller.hasNext()) {
 *         unmarshaller.next(YourObject.class);
 *     }
 * </pre>
 * or with the {@link #iterate(BiConsumer)} method:
 * <pre>
 *     unmarshaller.iterate((type, element) -&gt; doSomething(element));
 * </pre>
 * Don't forget to open the stream before trying to read in it.
 */
@Slf4j
public class StreamingUnmarshaller implements Closeable {

    private final Map<Class<?>, Unmarshaller> unmarshallerCache = new HashMap<>();
    private final Map<String, Class<?>> mapType = new HashMap<>();
    private XMLStreamReader xmlReader;

    /**
     * Creates a new streaming unmarshaller reading elements from the given types.
     * Please note that the given classes need the {@link XmlRootElement} annotation.
     *
     * @param types The list of element types that will be read by the unmarshaller
     * @throws IllegalArgumentException if the {@link XmlRootElement} annotation is missing for the given types
     * @throws JAXBException            if an error was encountered while creating the unmarshaller instances
     */
    public StreamingUnmarshaller(Class<?>... types) throws JAXBException {
        for (Class<?> type : types) {
            String key = getAnnotation(type, XmlRootElement.class).name();
            unmarshallerCache.put(type, createUnmarshaller(type));
            mapType.put(key, type);
        }
    }

    /**
     * Creates a new streaming unmarshaller reading elements from the given types.
     * Please note that the {@link Map} has to contain each type with its XML tag name
     * (equivalent to the value in {@link XmlRootElement} or {@link XmlElement})
     *
     * @param types The list of elements types with their name that will be read by the unmarshaller
     * @throws JAXBException if an error was encountered while creating the unmarshaller instances
     */
    public StreamingUnmarshaller(Map<Class<?>, String> types) throws JAXBException {
        for (Map.Entry<Class<?>, String> entry : types.entrySet()) {
            Class<?> type = entry.getKey();
            unmarshallerCache.put(type, createUnmarshaller(type));
            mapType.put(entry.getValue(), type);
        }
    }

    /**
     * Opens the given input stream in which the XML file has to be read.
     * It skips the beginning of the document with XML definition and the root element (container tag).
     * If an input stream is already open, it closes it before opening the new one.
     *
     * @param inputStream The input stream in which read the XML elements
     * @throws XMLStreamException if an error was encountered while creating the reader or while skipping tags
     */
    public synchronized void open(InputStream inputStream) throws XMLStreamException {
        open(inputStream, 1);
    }

    /**
     * Opens the given input stream in which the XML file has to be read.
     * It skips the beginning of the document with XML definition and a number of container tags
     * (putting 1 as {@code skipDepth} corresponds to only skip the root element).
     * If an input stream is already open, it closes it before opening the new one.
     *
     * @param inputStream The input stream in which read the XML elements
     * @param skipDepth   The number of container to skip before reaching the stream of desired elements
     * @throws XMLStreamException if an error was encountered while creating the reader or while skipping tags
     */
    public synchronized void open(InputStream inputStream, int skipDepth) throws XMLStreamException {
        if (xmlReader != null) {
            close();
        }

        XMLInputFactory factory = XMLInputFactory.newInstance();
        // Deny all access to external references
        factory.setProperty(IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(SUPPORT_DTD, false);
        xmlReader = factory.createXMLStreamReader(inputStream);
        skipDocumentStart(skipDepth);
    }

    /**
     * Skip the elements at the start of the document to reach the list to browse.
     * Override this method if you have a complex structure in the XML file before reaching the elements list.
     * Note that the parameter {@code skipDepth} may become irrelevant when reimplementing it depending on the
     * file structure complexity.
     *
     * @param skipDepth The number of containers to skip before reaching the stream of desired elements
     * @throws XMLStreamException if an error was encountered while skipping tags
     */
    protected void skipDocumentStart(int skipDepth) throws XMLStreamException {
        // Ignore headers
        skipElements(START_DOCUMENT, DTD);

        for (int i = 0; i < skipDepth; ++i) {
            // Ignore root element
            xmlReader.nextTag();
        }

        // If there's no tag, ignore root element's end
        skipElements(END_ELEMENT);
    }

    /**
     * Skips the given event types.
     *
     * @param elements The event types to ignore
     * @throws XMLStreamException if an error was encountered while skipping the elements
     */
    protected void skipElements(Integer... elements) throws XMLStreamException {
        int eventType = xmlReader.getEventType();

        List<Integer> types = Arrays.asList(elements);
        while (types.contains(eventType)) {
            eventType = xmlReader.next();
        }
    }

    /**
     * Creates a new unmarshaller for the given type.
     *
     * @param type The type of elements the unmarshaller has to handle
     * @return The unmarshaller created, capable of handling the conversion to the given element type
     * @throws JAXBException if an error was encountered while creating the unmarshaller
     */
    public Unmarshaller createUnmarshaller(Class<?> type) throws JAXBException {
        return JAXBContext.newInstance(type).createUnmarshaller();
    }

    /**
     * Gets the type of the next element in the stream.
     *
     * @return The next type or {@code null} when not found (in that case, add that type at class instantiation)
     * @throws XMLStreamException if an error was encountered while detecting the next state
     */
    public Class<?> getNextType() throws XMLStreamException {
        if (!hasNext()) {
            throw new XMLStreamException("There is no more element to read");
        }

        return Optional.ofNullable(xmlReader)
                .map(XMLStreamReader::getName)
                .map(QName::toString)
                .map(mapType::get)
                .orElseThrow(() -> new XMLStreamException("Unknown next type in the stream, " +
                        "check given ones in constructor or if skipDepth parameter in open method is correct"));
    }

    /**
     * Reads the next element from the stream.
     *
     * @param type The type of element to read
     * @param <T>  The element type
     * @return The element read from the stream
     * @throws XMLStreamException if there's no more element to read
     * @throws JAXBException      if there's a mismatch between the given type and the element type read
     * @throws JAXBException      if an error was encountered while unmarshalling the element
     */
    public synchronized <T> T next(Class<T> type) throws JAXBException, XMLStreamException {
        Class<?> nextType = getNextType();
        if (type == null || !type.equals(nextType)) {
            throw new JAXBException("Mismatch between next type " + nextType + " and given type " + type);
        }

        Unmarshaller unmarshaller = unmarshallerCache.get(type);
        T value = unmarshaller.unmarshal(xmlReader, type).getValue();

        skipElements(CHARACTERS, END_ELEMENT);
        return value;
    }

    /**
     * Indicates if there is one more element to read in the stream.
     *
     * @return {@code true} if there is at least one more element, {@code false} otherwise
     * @throws XMLStreamException if an error was encountered while detecting the next state
     */
    public boolean hasNext() throws XMLStreamException {
        return xmlReader.hasNext();
    }

    /**
     * Iterates over all elements with the given consumer.
     *
     * @param consumer The consumer called for each element of the stream
     * @throws XMLStreamException if an error was encountered while detecting the next state
     * @throws JAXBException      if an error was encountered while unmarshalling an element
     */
    public void iterate(BiConsumer<Class<?>, Object> consumer) throws JAXBException, XMLStreamException {
        while (hasNext()) {
            Class<?> type = getNextType();
            consumer.accept(type, next(type));
        }
    }

    /**
     * Closes the stream.
     */
    @Override
    public synchronized void close() {
        try {
            if (xmlReader != null) {
                xmlReader.close();
            }
        } catch (XMLStreamException e) {
            log.error("Unable to close XML stream reader", e);
        } finally {
            xmlReader = null;
        }
    }

}